package com.openchat.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openchat.domain.Avatar
import com.openchat.domain.AvatarDigest

private val FaceGreen = Color(0xFF07C160)

@Composable
fun Face(
    avatar: Avatar,
    initial: Char,
    size: Dp,
    bytesFor: ((AvatarDigest) -> ByteArray?)? = null,
    modifier: Modifier = Modifier,
) {
    val digest = (avatar as? Avatar.Image)?.digest
    val imageBitmap = remember(digest) {
        digest?.let { bytesFor?.invoke(it) }?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
    val shape = RoundedCornerShape(6.dp)
    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier
                .size(size)
                .clip(shape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(FaceGreen),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initial.toString(),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
