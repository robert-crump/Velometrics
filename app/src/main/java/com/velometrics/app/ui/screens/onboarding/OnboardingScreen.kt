package com.velometrics.app.ui.screens.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.velometrics.app.domain.model.FtpLevel
import com.velometrics.app.util.CyclingConstants

/** First-run onboarding (#238): "Your profile", then "Connect Dropbox". Both ways out land on Home. */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel) {
    val page by viewModel.page.collectAsState()
    val form by viewModel.form.collectAsState()
    val isDropboxConnected by viewModel.isDropboxConnected.collectAsState()

    when (page) {
        OnboardingPage.Profile -> ProfilePage(
            form = form,
            onChange = viewModel::updateForm,
            onContinue = viewModel::saveProfile
        )
        OnboardingPage.Dropbox -> {
            BackHandler(onBack = viewModel::backToProfile)
            // The OAuth result arrives in MainActivity.onResume; finish once it's in.
            LaunchedEffect(isDropboxConnected) { if (isDropboxConnected) viewModel.finish() }
            DropboxPage(onConnect = viewModel::connectDropbox, onLater = viewModel::finish)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProfilePage(
    form: ProfileForm,
    onChange: ((ProfileForm) -> ProfileForm) -> Unit,
    onContinue: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Your profile") }) },
        bottomBar = {
            Button(
                onClick = onContinue,
                enabled = form.profile != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp)
            ) { Text("Continue") }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Every ride is analysed with the profile in force when it's imported, so set it " +
                    "before the first sync. You can change it later in Settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionTitle("Weight")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    value = form.riderWeight,
                    onValueChange = { text -> onChange { it.withRiderWeight(text) } },
                    label = "Rider (kg)",
                    range = CyclingConstants.RIDER_WEIGHT_RANGE_KG,
                    modifier = Modifier.weight(1f)
                )
                NumberField(
                    value = form.bikeKitWeight,
                    onValueChange = { text -> onChange { it.withBikeKitWeight(text) } },
                    label = "Bike + kit (kg)",
                    range = CyclingConstants.BIKE_KIT_WEIGHT_RANGE_KG,
                    modifier = Modifier.weight(1f)
                )
            }

            SectionTitle("FTP")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FtpLevel.entries.forEach { level ->
                    FilterChip(
                        selected = form.level == level,
                        onClick = { onChange { it.withLevel(level) } },
                        label = { Text(level.label) }
                    )
                }
            }
            Text(
                form.level?.hint ?: "Your own FTP. Pick a level to estimate it instead.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            NumberField(
                value = form.ftp,
                onValueChange = { text -> onChange { it.withFtp(text) } },
                label = "FTP (W)",
                range = CyclingConstants.FTP_RANGE_W,
                supporting = form.ftpFormula ?: "I know my FTP",
                modifier = Modifier.fillMaxWidth()
            )

            SectionTitle("Max heart rate")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = form.birthYear,
                    onValueChange = { text -> onChange { it.withBirthYear(text) } },
                    label = { Text("Year of birth") },
                    isError = form.age == null,
                    supportingText = { Text("Not saved") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                NumberField(
                    value = form.maxHr,
                    onValueChange = { text -> onChange { it.withMaxHr(text) } },
                    label = "Max HR (bpm)",
                    range = CyclingConstants.MAX_HR_RANGE_BPM,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                form.maxHrFormula ?: "Your own max HR.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DropboxPage(onConnect: () -> Unit, onLater: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text("Connect Dropbox", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Velometrics imports the .fit files your bike computer saves to Dropbox " +
                "(${CyclingConstants.DEFAULT_DROPBOX_SYNC_FOLDER}) and keeps syncing new rides. " +
                "Access is read-only.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp)
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onConnect) { Text("Connect Dropbox") }
        TextButton(onClick = onLater) { Text("Later") }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    range: IntRange,
    modifier: Modifier = Modifier,
    supporting: String? = null
) {
    val outOfRange = value.trim().toIntOrNull()?.let { it !in range } ?: true
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = outOfRange,
        supportingText = when {
            outOfRange -> { { Text("${range.first}–${range.last}") } }
            supporting != null -> { { Text(supporting) } }
            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier
    )
}
