package cc.machado.audioblackbox.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cc.machado.audioblackbox.R
import cc.machado.audioblackbox.settings.SaveAudioFormat
import cc.machado.audioblackbox.ui.theme.*

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
                    shape = RoundedCornerShape(RADIUS_SM),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = CockpitSlate,
                        unfocusedContainerColor = CockpitSlate,
                        focusedBorderColor = FlightOrange,
                        unfocusedBorderColor = CockpitBorderStrong,
                        focusedLabelColor = FlightOrange,
                        unfocusedLabelColor = TextMuted,
                        focusedTextColor = TextStencil,
                        unfocusedTextColor = TextStencil,
                        focusedTrailingIconColor = TextMuted,
                        unfocusedTrailingIconColor = TextMuted,
                    ),
                    label = { Text(stringResource(R.string.settings_save_audio_format),
                        modifier = Modifier.testTag("saveFormatLabel")) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded,
                        modifier = Modifier.testTag("saveFormatIcon")) },
                    modifier = Modifier.fillMaxWidth().testTag("saveFormatField")
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.testTag("saveFormatPopup"),
                    containerColor = CockpitPanel,
                    shape = CARD_SHAPE,
                    border = BorderStroke(1.dp, CockpitBorderStrong),
                    // Keep the specified panel color instead of applying a tonal surface overlay.
                    tonalElevation = 0.dp,
                ) {
                    val itemColors = MenuDefaults.itemColors(
                        textColor = TextStencil,
                        disabledTextColor = TextDim,
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.settings_format_aac)) },
                        modifier = Modifier.testTag("saveFormatAac"),
                        colors = itemColors,
                        onClick = { expanded = false; onSelect(SaveAudioFormat.AAC) })
                    DropdownMenuItem(text = {
                        Column {
                            Text(stringResource(R.string.settings_format_opus))
                            if (opusAvailable == false) Text(stringResource(R.string.settings_format_opus_unavailable),
                                color = TextMuted,
                                modifier = Modifier.testTag("saveFormatReason"),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    },
                        enabled = opusAvailable == true,
                        modifier = Modifier.testTag("saveFormatOpus"),
                        colors = itemColors,
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
