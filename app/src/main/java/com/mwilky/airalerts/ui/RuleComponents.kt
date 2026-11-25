@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.mwilky.airalerts.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mwilky.airalerts.R
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.mwilky.airalerts.DndMode
import com.mwilky.airalerts.QuietHoursMode
import com.mwilky.airalerts.RuleConditions
import com.mwilky.airalerts.band.BuzzPattern

// The shared building blocks, so this app's screens read the same way.

/**
 * Large section header with the one prominent toggle for a whole feature.
 */
@Composable
fun SectionHero(
    title: String,
    statusText: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val container by animateColorAsState(
        targetValue = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "heroContainer"
    )
    val content = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AirAlertsShapes.CardShape,
        color = container,
        contentColor = content
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                AnimatedText(text = statusText, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                thumbContent = if (checked) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else null
            )
        }
    }
}

@Composable
fun SectionOffHint(text: String) {
    ListItem(
        leadingContent = { Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large)
    ) {
        Text(text)
    }
}

/**
 * Collapses a section's rows into a short "turned off" hint when the feature is disabled.
 */
@Composable
fun SectionBody(enabled: Boolean, offHint: String, content: @Composable () -> Unit) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    AnimatedContent(
        targetState = enabled,
        transitionSpec = {
            (fadeIn(effects) + expandVertically(spatial))
                .togetherWith(fadeOut(effects) + shrinkVertically(spatial))
                .using(SizeTransform(clip = false) { _, _ -> spatial })
        },
        label = "sectionBody"
    ) { on ->
        if (on) content() else SectionOffHint(offHint)
    }
}

/**
 * Text that cross-fades and resizes with the expressive motion scheme when its content changes.
 */
@Composable
fun AnimatedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified
) {
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = {
            fadeIn(effects).togetherWith(fadeOut(effects))
                .using(SizeTransform(clip = false) { _, _ -> spatial })
        },
        label = "animatedText"
    ) { value ->
        Text(text = value, style = style, color = color)
    }
}

/** Group title. */
@Composable
fun RuleGroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 8.dp)
    )
}

@Composable
fun EmptyRuleHint(text: String, icon: ImageVector) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        ),
        shapes = ListItemDefaults.shapes(shape = MaterialTheme.shapes.large),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * A segmented row with a switch. The text and switch are laid out directly rather than through
 * the list item's headline and trailing slots, so a wrapping description keeps the switch centred.
 */
@Composable
fun SwitchRow(
    index: Int,
    count: Int,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    conditions: RuleConditions? = null,
    onClick: (() -> Unit)? = null
) {
    SegmentedListItem(
        onClick = onClick ?: { onCheckedChange(!checked) },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(title)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                conditions?.let { ConditionChips(it, Modifier.padding(top = 6.dp)) }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

/**
 * One custom rule in a segmented group. A rule here is only buzz or stay quiet,
 * so a tap flips the switch rather than opening an editor; long-press offers delete.
 */
@Composable
fun RuleListItem(
    index: Int,
    count: Int,
    title: String,
    buzz: Boolean,
    onBuzzChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    leading: @Composable () -> Unit,
    conditions: RuleConditions = RuleConditions(),
    onEdit: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        SegmentedListItem(
            onClick = onEdit ?: { onBuzzChange(!buzz) },
            onLongClick = { menuOpen = true },
            onLongClickLabel = stringResource(R.string.rule_delete),
            shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            verticalAlignment = Alignment.CenterVertically,
            leadingContent = leading,
            supportingContent = {
                Column(
                    modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AnimatedText(text = stringResource(if (buzz) R.string.rule_buzzes else R.string.rule_quiet))
                    ConditionChips(conditions)
                }
            },
            trailingContent = {
                Switch(checked = buzz, onCheckedChange = onBuzzChange)
            }
        ) {
            Text(title)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rule_delete)) },
                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        }
    }
}

/**
 * A rule's overridden conditions as small chips; following the Settings tab shows none. Tertiary
 * when the rule buzzes anyway, muted when it stays quiet; the label spells the direction out too,
 * so colour isn't the only cue.
 */
@Composable
fun ConditionChips(conditions: RuleConditions, modifier: Modifier = Modifier) {
    val chips = buildList {
        when (conditions.dnd) {
            DndMode.INHERIT -> Unit
            DndMode.ALWAYS -> add(Triple(Icons.Rounded.DoNotDisturbOn, R.string.chip_dnd_always, true))
            DndMode.SKIP -> add(Triple(Icons.Rounded.DoNotDisturbOn, R.string.chip_dnd_skip, false))
        }
        when (conditions.quiet) {
            QuietHoursMode.INHERIT -> Unit
            QuietHoursMode.ALWAYS -> add(Triple(Icons.Rounded.Bedtime, R.string.chip_quiet_always, true))
            QuietHoursMode.SKIP -> add(Triple(Icons.Rounded.Bedtime, R.string.chip_quiet_skip, false))
        }
        when (conditions.pattern) {
            null -> Unit
            BuzzPattern.SINGLE -> add(Triple(Icons.Rounded.Vibration, R.string.chip_pattern_single, true))
            BuzzPattern.DOUBLE -> add(Triple(Icons.Rounded.Vibration, R.string.chip_pattern_double, true))
            BuzzPattern.LONG -> add(Triple(Icons.Rounded.Vibration, R.string.chip_pattern_long, true))
        }
    }
    if (chips.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chips.forEach { (icon, label, buzzes) ->
            Surface(
                shape = CircleShape,
                color = if (buzzes) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (buzzes) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(text = stringResource(label), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** A connected toggle-button strip for picking one of a few options. */
@Composable
internal fun <T> ConnectedChoice(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { optionIndex, (option, label) ->
            ToggleButton(
                checked = selected == option,
                onCheckedChange = { onSelect(option) },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
                // On a surfaceContainer background the default unchecked colour would hide the
                // unselected options.
                colors = ToggleButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shapes = when (optionIndex) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** A titled [ConnectedChoice] whose description changes with the option picked. */
@Composable
internal fun <T> ChoiceGroup(
    title: String,
    description: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        AnimatedText(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ConnectedChoice(options = options, selected = selected, onSelect = onSelect)
    }
}

/** A contact rule's leading badge. */
@Composable
fun ContactBadge() {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun AddRuleButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(text)
    }
}

@Composable
fun ButtonLabel(icon: ImageVector?, text: String) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    }
    Text(text)
}
