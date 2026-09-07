package com.jmwl.gostudio.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 异步解码 App 界面项目的图标文件（icon.png，与打包 APK 用的是同一份）。
 * 文件缺失或解码失败返回 null，由调用方回退到占位图标。
 */
@Composable
fun remember_project_icon_bitmap(path: String): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        if (path.isBlank()) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.asImageBitmap()
        }
    }
    return bitmap
}

/** 项目图标头像：圆角裁切 + 居中裁剪（非正方形图标不变形）。 */
@Composable
fun project_icon_image(bitmap: ImageBitmap, size: Dp, corner: Dp = 14.dp) {
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier.size(size).clip(RoundedCornerShape(corner)),
        contentScale = ContentScale.Crop
    )
}
