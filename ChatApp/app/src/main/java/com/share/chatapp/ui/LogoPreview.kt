package com.share.chatapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.share.chatapp.R

@Preview(showBackground = true)
@Composable
fun LogoPreview() {
    Box(
        modifier = Modifier
            .size(200.dp)
            .padding(20.dp)
            .clip(RoundedCornerShape(40.dp))
            .background(Color(0xFF075E54)), // The Emerald Green Background
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = com.share.chatapp.R.drawable.ic_launcher_foreground),
            contentDescription = "Logo Foreground",
            modifier = Modifier.size(120.dp)
        )
    }
}
