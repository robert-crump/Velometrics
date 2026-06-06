package com.velometrics.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.velometrics.app.domain.model.PoiWithDistances
import com.velometrics.app.util.FormatUtils

@Composable
fun PoiPopupCard(
    poiWithDistances: PoiWithDistances,
    onOpenInMaps: () -> Unit,
    modifier: Modifier = Modifier
) {
    val poi = poiWithDistances.poi
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = poi.name.ifEmpty { "Unnamed" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = FormatUtils.categoryDisplayName(poi.category),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                poi.openingHours?.let { hours ->
                    Text(
                        text = hours.replace("; ", "\n").replace(";", "\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                poiWithDistances.airDistanceM?.let { m ->
                    Text(
                        text = "${FormatUtils.formatPoiDistance(m)} away",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onOpenInMaps) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = "Open in Google Maps",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

fun openPoiInGoogleMaps(context: Context, poiWD: PoiWithDistances) {
    val poi = poiWD.poi
    val encodedName = Uri.encode(poi.name.ifEmpty { FormatUtils.categoryDisplayName(poi.category) })
    val uri = Uri.parse("geo:${poi.lat},${poi.lon}?q=${poi.lat},${poi.lon}($encodedName)")
    try { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
}
