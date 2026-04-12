package com.cyclegraph.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val fatColor = Color(0xFFFFEE58)
private val carbColor = Color(0xFFFFA726)

@Composable
fun CaloriesCard(fatGrams: Double, carbGrams: Double) {
    val fatKcal = fatGrams * 9.3
    val carbKcal = carbGrams * 4.1
    val totalKcal = fatKcal + carbKcal

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Calories",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Fat: %.1f g (%.0f kcal)".format(fatGrams, fatKcal),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Carbs: %.1f g (%.0f kcal)".format(carbGrams, carbKcal),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Total: %.0f kcal".format(totalKcal),
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Stacked bar showing fat/carb ratio
            if (totalKcal > 0) {
                val fatFraction = (fatKcal / totalKcal).toFloat()
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                ) {
                    val fatWidth = size.width * fatFraction
                    drawRect(
                        color = fatColor,
                        topLeft = Offset.Zero,
                        size = Size(fatWidth, size.height)
                    )
                    drawRect(
                        color = carbColor,
                        topLeft = Offset(fatWidth, 0f),
                        size = Size(size.width - fatWidth, size.height)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Fat %.0f%%".format(fatFraction * 100),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Carbs %.0f%%".format((1 - fatFraction) * 100),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
