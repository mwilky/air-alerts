@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.mwilky.airalerts.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.mwilky.airalerts.Licensing
import com.mwilky.airalerts.R

/**
 * The free trial or unlock, in the same shape as the status cards. Tertiary while the trial runs
 * or once unlocked, so it stands apart from the band's status; error once the trial is over.
 */
@Composable
fun LicenseCard(status: Licensing.Status, onBuy: () -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val price = status.priceText
    val expired = status.trialExpired && !status.purchased

    val title: String
    val statusText: String
    val subtitle: String
    when {
        status.purchased -> {
            title = stringResource(R.string.license_title_unlocked)
            statusText = stringResource(R.string.license_status_unlocked)
            subtitle = stringResource(R.string.license_desc_unlocked)
        }
        expired -> {
            title = stringResource(R.string.license_title_expired)
            statusText = stringResource(R.string.license_status_expired)
            subtitle = stringResource(R.string.license_desc_expired)
        }
        else -> {
            title = stringResource(R.string.license_title_trial)
            statusText = pluralStringResource(R.plurals.license_days_left, status.trialDaysLeft, status.trialDaysLeft)
            subtitle = if (price != null) stringResource(R.string.license_desc_trial_price, price) else stringResource(R.string.license_desc_trial)
        }
    }
    val accent = if (expired) c.error else c.tertiary
    val onAccent = if (expired) c.onError else c.onTertiary

    ExpressiveStatusCard(
        title = title,
        subtitle = subtitle,
        icon = Icons.Rounded.WorkspacePremium,
        statusText = statusText,
        accentColor = accent,
        containerColor = (if (expired) c.errorContainer else c.tertiaryContainer).copy(alpha = 0.45f),
        contentColor = if (expired) c.onErrorContainer else c.onTertiaryContainer,
        modifier = modifier,
        bottomAction = if (status.purchased) null else {
            {
                Button(
                    onClick = onBuy,
                    modifier = Modifier.fillMaxWidth(),
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent)
                ) {
                    Text(if (price != null) stringResource(R.string.license_btn_buy_price, price) else stringResource(R.string.license_btn_buy))
                }
            }
        }
    )
}
