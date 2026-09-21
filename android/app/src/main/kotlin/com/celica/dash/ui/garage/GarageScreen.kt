package com.celica.dash.ui.garage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.celica.dash.ui.DashViewModel
import com.celica.dash.ui.shell.Rail
import com.celica.dash.ui.shell.Tile

/**
 * The garage: what the site's electrical, issues, garage and specs pages hold, as native views
 * behind one rail. Tap a manual reference anywhere in here and it opens in the Manual section.
 */
@Composable
fun GarageScreen(vm: DashViewModel, openManualPage: (Int) -> Unit) {
    // only what the car pack has; the service log is the owner's own and is always there
    val site = vm.site
    val panels = remember {
        listOfNotNull(
            if (site.fusebox != null) "Fusebox" else null,
            if (site.issues.isNotEmpty()) "Issues" else null,
            "Service",
            if (site.specs.isNotEmpty()) "Specs" else null,
            if (site.checks.isNotEmpty()) "Checks" else null,
        )
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Tile(Modifier.fillMaxSize().padding(20.dp, 14.dp, 20.dp, 14.dp)) {
        Row(Modifier.fillMaxSize()) {
            Rail(panels, tab.coerceIn(0, panels.size - 1), onSelect = { tab = it })
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (panels.getOrNull(tab)) {
                    "Fusebox" -> FuseboxPanel(vm, openManualPage)
                    "Issues" -> IssuesPanel(vm, openManualPage)
                    "Specs" -> SpecsPanel(vm, openManualPage)
                    "Checks" -> ChecksPanel(vm, openManualPage)
                    else -> ServicePanel(vm)
                }
            }
        }
    }
}

/** What a panel shows when the car pack has nothing for it. */
@Composable
fun EmptyPanel(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.material3.Text(text, style = com.celica.dash.ui.theme.Body.copy(color = com.celica.dash.ui.theme.Ink.txDim))
    }
}
