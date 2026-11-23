@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.mwilky.airalerts.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PhoneCallback
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mwilky.airalerts.AlertSettings
import com.mwilky.airalerts.AppRule
import com.mwilky.airalerts.ContactRule
import com.mwilky.airalerts.R

/** Everything the Home pages can ask for, so the page composables take state and lambdas only. */
data class HomeActions(
    val onToggleCalls: (Boolean) -> Unit = {},
    val onToggleCallFavourites: (Boolean) -> Unit = {},
    val onToggleCallContacts: (Boolean) -> Unit = {},
    val onToggleCallUnknown: (Boolean) -> Unit = {},
    val onSaveCallerRule: (ContactRule) -> Unit = {},
    val onDeleteCallerRule: (ContactRule) -> Unit = {},
    val onAddCaller: () -> Unit = {},
    val onToggleNotifications: (Boolean) -> Unit = {},
    val onToggleNotifFavourites: (Boolean) -> Unit = {},
    val onToggleNotifOthers: (Boolean) -> Unit = {},
    val onSaveSenderRule: (ContactRule) -> Unit = {},
    val onDeleteSenderRule: (ContactRule) -> Unit = {},
    val onAddSender: () -> Unit = {},
    val onSaveAppRule: (AppRule) -> Unit = {},
    val onDeleteAppRule: (AppRule) -> Unit = {},
    val onAddApp: () -> Unit = {}
)

/** The Calls page: its switch, the default rows for favourites, contacts and unknown numbers, then caller rules. */
@Composable
fun ColumnScope.HomeCallsPage(settings: AlertSettings, actions: HomeActions) {
    SectionHero(
        title = stringResource(R.string.calls_hero_title),
        statusText = heroStatus(settings.calls, settings.callerRules.size),
        icon = Icons.Rounded.Call,
        checked = settings.calls,
        onCheckedChange = actions.onToggleCalls
    )
    SectionBody(enabled = settings.calls, offHint = stringResource(R.string.calls_off_hint)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SwitchRow(
                    index = 0,
                    count = 3,
                    title = stringResource(R.string.calls_fav_title),
                    description = stringResource(R.string.calls_fav_desc),
                    checked = settings.callFavourites,
                    onCheckedChange = actions.onToggleCallFavourites
                )
                SwitchRow(
                    index = 1,
                    count = 3,
                    title = stringResource(R.string.calls_contacts_title),
                    description = stringResource(R.string.calls_contacts_desc),
                    checked = settings.callContacts,
                    onCheckedChange = actions.onToggleCallContacts
                )
                SwitchRow(
                    index = 2,
                    count = 3,
                    title = stringResource(R.string.calls_unknown_title),
                    description = stringResource(R.string.calls_unknown_desc),
                    checked = settings.callUnknown,
                    onCheckedChange = actions.onToggleCallUnknown
                )
            }
            ContactRuleGroup(
                header = stringResource(R.string.calls_rules_header, settings.callerRules.size),
                emptyHint = stringResource(R.string.calls_no_rules),
                emptyIcon = Icons.AutoMirrored.Rounded.PhoneCallback,
                rules = settings.callerRules,
                addText = stringResource(R.string.calls_add_btn),
                onSave = actions.onSaveCallerRule,
                onDelete = actions.onDeleteCallerRule,
                onAdd = actions.onAddCaller
            )
            PageNote(stringResource(R.string.calls_desc))
        }
    }
}

/** The Notifications page: its switch, favourites and the catch-all row, then contact and app rules. */
@Composable
fun ColumnScope.HomeNotifsPage(settings: AlertSettings, actions: HomeActions) {
    SectionHero(
        title = stringResource(R.string.notifs_hero_title),
        statusText = heroStatus(settings.notifications, settings.senderRules.size + settings.appRules.size),
        icon = Icons.Rounded.Notifications,
        checked = settings.notifications,
        onCheckedChange = actions.onToggleNotifications
    )
    SectionBody(enabled = settings.notifications, offHint = stringResource(R.string.notifs_off_hint)) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SwitchRow(
                    index = 0,
                    count = 2,
                    title = stringResource(R.string.notifs_fav_title),
                    description = stringResource(R.string.notifs_fav_desc),
                    checked = settings.notifFavourites,
                    onCheckedChange = actions.onToggleNotifFavourites
                )
                SwitchRow(
                    index = 1,
                    count = 2,
                    title = stringResource(R.string.notifs_others_title),
                    description = stringResource(R.string.notifs_others_desc),
                    checked = settings.notifOthers,
                    onCheckedChange = actions.onToggleNotifOthers
                )
            }
            ContactRuleGroup(
                header = stringResource(R.string.notifs_contact_rules_header, settings.senderRules.size),
                emptyHint = stringResource(R.string.notifs_no_contact_rules),
                emptyIcon = Icons.AutoMirrored.Rounded.Message,
                rules = settings.senderRules,
                addText = stringResource(R.string.notifs_add_contact_btn),
                onSave = actions.onSaveSenderRule,
                onDelete = actions.onDeleteSenderRule,
                onAdd = actions.onAddSender
            )
            RuleGroupHeader(stringResource(R.string.notifs_app_rules_header, settings.appRules.size))
            if (settings.appRules.isEmpty()) {
                EmptyRuleHint(stringResource(R.string.notifs_no_app_rules), Icons.Rounded.Apps)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    settings.appRules.forEachIndexed { index, rule ->
                        key(rule.packageName) {
                            RuleListItem(
                                index = index,
                                count = settings.appRules.size,
                                title = rule.appName,
                                buzz = rule.buzz,
                                onBuzzChange = { actions.onSaveAppRule(rule.copy(buzz = it)) },
                                onDelete = { actions.onDeleteAppRule(rule) },
                                leading = { AppBadge(rule.packageName) }
                            )
                        }
                    }
                }
            }
            AddRuleButton(stringResource(R.string.notifs_add_app_btn), Icons.Rounded.Add, actions.onAddApp)
            PageNote(stringResource(R.string.notifications_desc))
        }
    }
}

@Composable
private fun ContactRuleGroup(
    header: String,
    emptyHint: String,
    emptyIcon: androidx.compose.ui.graphics.vector.ImageVector,
    rules: List<ContactRule>,
    addText: String,
    onSave: (ContactRule) -> Unit,
    onDelete: (ContactRule) -> Unit,
    onAdd: () -> Unit
) {
    RuleGroupHeader(header)
    if (rules.isEmpty()) {
        EmptyRuleHint(emptyHint, emptyIcon)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            rules.forEachIndexed { index, rule ->
                key(rule.id) {
                    RuleListItem(
                        index = index,
                        count = rules.size,
                        title = rule.name,
                        buzz = rule.buzz,
                        onBuzzChange = { onSave(rule.copy(buzz = it)) },
                        onDelete = { onDelete(rule) },
                        leading = { ContactBadge() }
                    )
                }
            }
        }
    }
    AddRuleButton(addText, Icons.Rounded.PersonAdd, onAdd)
}

/** Small print under a page's rules, like the unofficial note at the foot of each page. */
@Composable
internal fun PageNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    )
}

@Composable
private fun heroStatus(on: Boolean, ruleCount: Int): String {
    if (!on) return stringResource(R.string.hero_status_off)
    val rules = if (ruleCount == 0) {
        stringResource(R.string.rule_count_none)
    } else {
        pluralStringResource(R.plurals.rule_count, ruleCount, ruleCount)
    }
    return stringResource(R.string.hero_status_on, rules)
}

/**
 * A connected toggle group, one button per page, that mirrors the pager position.
 * Only the selected tab carries its label; the others shrink to their icon.
 */
@Composable
internal fun HomePageToggle(
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val tabs = listOf(
        Icons.Rounded.Call to R.string.home_tab_calls,
        Icons.Rounded.Notifications to R.string.home_tab_notifications
    )
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()

    Row(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            // The gap below the strip lives here, not on the pages, so it stays put while they scroll.
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, (icon, labelRes) ->
            val selected = selectedIndex == index
            val label = stringResource(labelRes)
            ToggleButton(
                checked = selected,
                onCheckedChange = { onSelect(index) },
                // Circular while it's only an icon, rounded rectangle once it carries a label.
                shapes = ToggleButtonShapes(
                    shape = CircleShape,
                    pressedShape = RoundedCornerShape(14.dp),
                    checkedShape = RoundedCornerShape(18.dp)
                ),
                // Surface tones rather than primary: the selected tab is a step darker than the
                // unselected ones instead of a colour change.
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    checkedContentColor = MaterialTheme.colorScheme.onSurface
                ),
                // 24dp icon plus 12dp either side makes an exact circle at this height.
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier
                    .then(if (selected) Modifier.weight(1f, fill = false) else Modifier)
                    .height(TabHeight)
                    .semantics { role = Role.RadioButton }
            ) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
                AnimatedVisibility(
                    visible = selected,
                    enter = fadeIn(effects) + expandHorizontally(spatial),
                    exit = fadeOut(effects) + shrinkHorizontally(spatial)
                ) {
                    Row {
                        Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private val TabHeight = 48.dp
