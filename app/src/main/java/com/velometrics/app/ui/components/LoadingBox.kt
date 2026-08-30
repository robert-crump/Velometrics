package com.velometrics.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * Full-size, centered loading spinner. [modifier] is appended after the internal `fillMaxSize()`
 * (e.g. `Modifier.padding(padding)`), matching each call site's own pre-existing modifier chain —
 * some screens apply the Scaffold padding here, others don't; that inconsistency is preserved by
 * each call site rather than normalized here.
 */
@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(
        modifier = Modifier.fillMaxSize().then(modifier),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

/**
 * Full-size, centered "not found"/empty-state message. [style]/[color] default to plain `Text`
 * behavior so the three "X not found" call sites are unchanged; AllTimeStatsScreen's "No rides
 * recorded yet" empty state passes its own distinct styling, preserved as-is.
 */
@Composable
fun NotFoundBox(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    Box(
        modifier = Modifier.fillMaxSize().then(modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, style = style, color = color)
    }
}
