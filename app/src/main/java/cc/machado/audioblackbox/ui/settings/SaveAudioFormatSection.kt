package cc.machado.audioblackbox.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.settings.SaveAudioFormat
import cc.machado.audioblackbox.ui.theme.AvionicsCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SaveAudioFormatSection(
    selected: SaveAudioFormat?,
    opusAvailable: Boolean?,
    failed: Boolean,
    onSelect: (SaveAudioFormat) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    AvionicsCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = when (selected) {
                        SaveAudioFormat.AAC -> stringResource(R.string.settings_format_aac)
                        SaveAudioFormat.OPUS -> stringResource(R.string.settings_format_opus)
                        null -> ""
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_save_audio_format)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_format_aac)) },
                        onClick = { expanded = false; onSelect(SaveAudioFormat.AAC) })
                    DropdownMenuItem(text = {
                        Column {
                            Text(stringResource(R.string.settings_format_opus))
                            if (opusAvailable == false) Text(stringResource(R.string.settings_format_opus_unavailable),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    },
                        enabled = opusAvailable == true,
                        onClick = { expanded = false; onSelect(SaveAudioFormat.OPUS) })
                }
            }
            if (selected != null) {
                Text(stringResource(if (selected == SaveAudioFormat.AAC) R.string.settings_format_aac_helper
                    else R.string.settings_format_opus_helper), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(if (selected == SaveAudioFormat.AAC) R.string.settings_format_aac_live
                    else R.string.settings_format_opus_live), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (opusAvailable == false) {
                Text(stringResource(R.string.settings_format_opus_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (failed) {
                Text(stringResource(R.string.settings_format_save_error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}
