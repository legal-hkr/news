@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.settings

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sh.hnet.news.R
import sh.hnet.news.ui.components.SettingsGroup
import sh.hnet.news.ui.components.SettingsPage
import sh.hnet.news.ui.components.settingsRowColors

/** A license the shipped components come under; [text] is its full text in res/raw. */
private enum class License(@param:StringRes val label: Int, @param:RawRes val text: Int) {
    APACHE(R.string.label_apache_license, R.raw.apache_license_2_0),
    MIT_JSOUP(R.string.label_mit_license, R.raw.mit_license_jsoup),
}

/**
 * What ships in the app, with its license (checked against the release runtime classpath; update
 * when a dependency is added). The MIT License's text carries the copyright line of its component,
 * so each MIT component has a text of its own.
 */
private val COMPONENTS = listOf(
    "AndroidX (Jetpack Compose, Material 3, Navigation 3, DataStore, Lifecycle, Activity, Core)" to License.APACHE,
    "Kotlin" to License.APACHE,
    "kotlinx.coroutines" to License.APACHE,
    "kotlinx.serialization" to License.APACHE,
    "jsoup" to License.MIT_JSOUP,
    "Guava" to License.APACHE,
    "Okio" to License.APACHE,
    "JSpecify" to License.APACHE,
    "JetBrains Java Annotations" to License.APACHE,
    "Material Symbols" to License.APACHE,
)

/** Settings › About › Open-source licenses, as Music's: each component and its license, the text on tap. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val resources = LocalResources.current
    var showing by rememberSaveable { mutableStateOf<License?>(null) }
    SettingsPage(title = stringResource(R.string.label_licenses), onBack = onBack) {
        SettingsGroup {
            COMPONENTS.forEach { (name, license) ->
                row { shapes ->
                    SegmentedListItem(
                        onClick = { showing = license },
                        shapes = shapes,
                        colors = settingsRowColors(),
                        supportingContent = { Text(stringResource(license.label)) },
                    ) {
                        Text(name)
                    }
                }
            }
        }
    }
    showing?.let { license ->
        val licenseText = remember(resources, license) { resources.openRawResource(license.text).bufferedReader().use { it.readText() } }
        AlertDialog(
            onDismissRequest = { showing = null },
            title = { Text(stringResource(license.label)) },
            text = {
                SelectionContainer(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    Text(licenseText, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showing = null }) { Text(stringResource(R.string.button_close)) } },
        )
    }
}
