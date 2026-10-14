package com.simoesctt.neurocardia.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaColors
import com.simoesctt.neurocardia.ui.theme.NeuroCardiaTypography

private val MIME_ALL = arrayOf("*/*")

/**
 * A button that opens the Android system file picker and invokes
 * [onFilePicked] with the selected Uri.
 *
 * The picker accepts all MIME types. The CSV reader validates its own
 * input, so restricting the picker was redundant and could hide files
 * whose MIME type Android does not classify as expected.
 */
@Composable
fun FilePickerButton(
    label: String,
    modifier: Modifier = Modifier,
    onFilePicked: (Uri) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        android.util.Log.d("NeuroCardia", "FilePickerButton: result uri = $uri")
        if (uri != null) onFilePicked(uri)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NeuroCardiaColors.CyanGlow.copy(alpha = 0.12f))
            .clickable {
                android.util.Log.d("NeuroCardia", "FilePickerButton: click, launching picker")
                launcher.launch(MIME_ALL)
            }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "+",
            style = NeuroCardiaTypography.titleLarge,
            color = NeuroCardiaColors.CyanGlow
        )
        Text(
            text = label,
            style = NeuroCardiaTypography.titleLarge,
            color = NeuroCardiaColors.CyanGlow
        )
    }
}

/**
 * A compact text-only picker used in the header row. Same launcher
 * behaviour as [FilePickerButton], no background chrome.
 */
@Composable
fun FilePickerTextButton(
    label: String,
    modifier: Modifier = Modifier,
    onFilePicked: (Uri) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onFilePicked(uri)
    }

    Text(
        text = label,
        style = NeuroCardiaTypography.titleLarge,
        color = NeuroCardiaColors.CyanGlow,
        modifier = modifier
            .clickable { launcher.launch(MIME_ALL) }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * A button that opens the Android folder picker (OpenDocumentTree) and
 * invokes [onFolderPicked] with the selected tree Uri. Visually
 * identical to [FilePickerButton] so the two sit together cleanly.
 */
@Composable
fun FolderPickerButton(
    label: String,
    modifier: Modifier = Modifier,
    onFolderPicked: (Uri) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) onFolderPicked(uri)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NeuroCardiaColors.CyanGlow.copy(alpha = 0.12f))
            .clickable { launcher.launch(null) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "+",
            style = NeuroCardiaTypography.titleLarge,
            color = NeuroCardiaColors.CyanGlow
        )
        Text(
            text = label,
            style = NeuroCardiaTypography.titleLarge,
            color = NeuroCardiaColors.CyanGlow
        )
    }
}

/**
 * A compact folder-picker text button for the header row.
 */
@Composable
fun FolderPickerTextButton(
    label: String,
    modifier: Modifier = Modifier,
    onFolderPicked: (Uri) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) onFolderPicked(uri)
    }

    Text(
        text = label,
        style = NeuroCardiaTypography.titleLarge,
        color = NeuroCardiaColors.CyanGlow,
        modifier = modifier
            .clickable { launcher.launch(null) }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}
