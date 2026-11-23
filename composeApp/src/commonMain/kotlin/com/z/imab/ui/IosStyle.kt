package com.z.imab.ui

import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * IMAB looks like an iPhone app on every device: Apple's system colors, the iOS type scale, grouped
 * inset lists on a grey background, plain blue text buttons and tinted (not outlined) buttons.
 */

/** Apple's system colors (light / dark), as iOS defines them. */
object Ios {
    val blue = Color(0xFF007AFF)
    val blueDark = Color(0xFF0A84FF)
    val green = Color(0xFF248A3D) // the accessible green, readable as text on white
    val greenDark = Color(0xFF30D158)
    val red = Color(0xFFE0352B)
    val redDark = Color(0xFFFF453A)
    val orange = Color(0xFFC25E00) // readable as text; the bright orange is for fills
    val orangeFill = Color(0xFFFF9500)
    val orangeDark = Color(0xFFFF9F0A)

    val groupedBackground = Color(0xFFF2F2F7)
    val cell = Color.White
    val separator = Color(0xFFC6C6C8)
    val secondaryLabel = Color(0xFF6C6C70)
    val fill = Color(0xFFEFEFF4)
    val barBackground = Color(0xFFF9F9F9)

    val groupedBackgroundDark = Color.Black
    val cellDark = Color(0xFF1C1C1E)
    val elevatedDark = Color(0xFF2C2C2E)
    val separatorDark = Color(0xFF38383A)
    val secondaryLabelDark = Color(0xFF98989F)
    val fillDark = Color(0xFF2C2C2E)
    val barBackgroundDark = Color(0xFF121212)
}

val LightColors = lightColorScheme(
    primary = Ios.blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0EEFF),
    onPrimaryContainer = Color(0xFF0040DD),
    secondary = Color(0xFF8E8E93),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E5EA),
    onSecondaryContainer = Color.Black,
    tertiary = Ios.orange,
    tertiaryContainer = Color(0xFFFFF0DB),
    onTertiaryContainer = Color(0xFF8A4300),
    error = Ios.red,
    errorContainer = Color(0xFFFFE5E3),
    onErrorContainer = Color(0xFFB0161B),
    background = Ios.groupedBackground,
    onBackground = Color.Black,
    surface = Ios.groupedBackground,
    onSurface = Color.Black,
    surfaceVariant = Ios.fill,
    onSurfaceVariant = Ios.secondaryLabel,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Ios.cell,
    surfaceContainerLow = Ios.cell,
    surfaceContainer = Ios.cell,
    surfaceContainerHigh = Ios.cell,
    surfaceContainerHighest = Ios.cell, // cards
    outline = Ios.separator,
    outlineVariant = Ios.separator,
)

val DarkColors = darkColorScheme(
    primary = Ios.blueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0B2A4A),
    onPrimaryContainer = Color(0xFF9ACBFF),
    secondary = Color(0xFF8E8E93),
    onSecondary = Color.Black,
    secondaryContainer = Ios.elevatedDark,
    onSecondaryContainer = Color.White,
    tertiary = Ios.orangeDark,
    tertiaryContainer = Color(0xFF3D2600),
    onTertiaryContainer = Color(0xFFFFD08A),
    error = Ios.redDark,
    errorContainer = Color(0xFF3F1210),
    onErrorContainer = Color(0xFFFFB4AB),
    background = Ios.groupedBackgroundDark,
    onBackground = Color.White,
    surface = Ios.groupedBackgroundDark,
    onSurface = Color.White,
    surfaceVariant = Ios.fillDark,
    onSurfaceVariant = Ios.secondaryLabelDark,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Ios.cellDark,
    surfaceContainerLow = Ios.cellDark,
    surfaceContainer = Ios.elevatedDark,
    surfaceContainerHigh = Ios.elevatedDark,
    surfaceContainerHighest = Ios.cellDark, // cards
    outline = Ios.separatorDark,
    outlineVariant = Ios.separatorDark,
)

private fun style(size: Int, weight: FontWeight, line: Int, tracking: Double) =
    TextStyle(fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, letterSpacing = tracking.sp)

/**
 * The iOS type scale (large title 34, body 17, footnote 13…), with iOS's tight letter spacing in
 * place of Material's wide tracking.
 */
val ImabTypography = Typography(
    displayLarge = style(40, FontWeight.Bold, 46, 0.2),
    displayMedium = style(36, FontWeight.Bold, 42, 0.2),
    displaySmall = style(34, FontWeight.Bold, 41, 0.2), // large title
    headlineLarge = style(28, FontWeight.Bold, 34, 0.2), // title 1
    headlineMedium = style(22, FontWeight.Bold, 28, -0.2), // title 2
    headlineSmall = style(20, FontWeight.SemiBold, 25, -0.3), // title 3: dialog titles
    titleLarge = style(20, FontWeight.SemiBold, 25, -0.3),
    titleMedium = style(17, FontWeight.SemiBold, 22, -0.3), // headline
    titleSmall = style(15, FontWeight.SemiBold, 20, -0.15),
    bodyLarge = style(17, FontWeight.Normal, 22, -0.3), // body
    bodyMedium = style(15, FontWeight.Normal, 20, -0.15), // subheadline
    bodySmall = style(13, FontWeight.Normal, 18, 0.0), // footnote
    labelLarge = style(16, FontWeight.Medium, 21, -0.2),
    labelMedium = style(13, FontWeight.Medium, 18, 0.0),
    labelSmall = style(11, FontWeight.Medium, 13, 0.05),
)

val ImabShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/** Corner radius of grouped lists. */
val GroupCorner: Dp = 12.dp

/** Space between a grouped list and the screen edge. */
val GroupInset: Dp = 12.dp

/** The color of a row in a grouped list. */
val cellColor: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLowest

/** A light, tappable fill for form rows and text fields. */
val fieldColor: Color @Composable get() = MaterialTheme.colorScheme.surfaceVariant

/** The screen's title, large and left-aligned as on iOS, with optional buttons on its right. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
    }
}

/** Small grey text above a grouped list, naming the group. */
@Composable
fun GroupHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = GroupInset + 16.dp, end = GroupInset + 16.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Small grey text under a grouped list, explaining it. */
@Composable
fun GroupFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth().padding(start = GroupInset + 16.dp, end = GroupInset + 16.dp, top = 6.dp, bottom = 4.dp),
    )
}

/** A whole grouped list: rounded, on the cell color, inset from the screen edges. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = GroupInset).clip(RoundedCornerShape(GroupCorner)).background(cellColor),
        content = content,
    )
}

/** The shape of one row of a grouped list in a LazyColumn: the first and last rows round the group's corners. */
fun groupShape(first: Boolean, last: Boolean): Shape = RoundedCornerShape(
    topStart = if (first) GroupCorner else 0.dp,
    topEnd = if (first) GroupCorner else 0.dp,
    bottomStart = if (last) GroupCorner else 0.dp,
    bottomEnd = if (last) GroupCorner else 0.dp,
)

/** One row of a grouped list laid out item by item (in a LazyColumn). */
@Composable
fun Modifier.groupRow(first: Boolean, last: Boolean): Modifier =
    this.fillMaxWidth().padding(horizontal = GroupInset).clip(groupShape(first, last)).background(cellColor)

/** The hairline between rows of a group, starting where the text does. */
@Composable
fun RowSeparator(start: Dp = 16.dp) {
    HorizontalDivider(Modifier.padding(start = start), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** A button with a light tint of the accent color: the iOS "bordered" button, in place of outlined ones. */
@Composable
fun TintedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = accent.copy(alpha = 0.12f),
            contentColor = accent,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        ),
        elevation = null,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * A row of a form that opens a chooser: the label on the left, the current value on the right,
 * on a light fill, as in an iOS form. Where that doesn't fit, the label goes small above the value.
 */
@Composable
fun FormRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: Boolean = false,
    chevron: Boolean = true,
    /** False for a row inside a grouped list, which is already on the cell color. */
    filled: Boolean = true,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.clip(if (filled) MaterialTheme.shapes.medium else RoundedCornerShape(0.dp))
            .background(if (filled) fieldColor else Color.Transparent).clickable(onClick = onClick),
    ) {
        val valueColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (placeholder) 0.7f else 1f)
        val narrow = maxWidth < 240.dp
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = if (narrow) 6.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (narrow) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(value, color = if (placeholder) valueColor else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(label, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                Spacer(Modifier.width(12.dp))
                Text(value, modifier = Modifier.weight(1f), color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            if (chevron) {
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** A text field on a light fill with its label inside and no outline, as in an iOS form. */
@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    supportingText: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
) {
    val fill = fieldColor
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        shape = MaterialTheme.shapes.medium,
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            unfocusedContainerColor = fill,
            focusedContainerColor = fill,
            disabledContainerColor = fill.copy(alpha = 0.5f),
            errorContainerColor = fill,
            unfocusedIndicatorColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * One choice of a few (Outflow / Inflow, By year / By month): a capsule that's grey, or filled
 * with the accent color when chosen.
 */
@Composable
fun ChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val accent = MaterialTheme.colorScheme.primary
    androidx.compose.material3.Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = if (selected) accent else fieldColor,
        contentColor = when {
            selected -> MaterialTheme.colorScheme.onPrimary
            enabled -> MaterialTheme.colorScheme.onSurface
            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        modifier = modifier,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            ) { label() }
        }
    }
}

/** An iOS switch: green when on. */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val dark = MaterialTheme.colorScheme.background.let { it.red + it.green + it.blue } < 1.5f
    androidx.compose.material3.Switch(
        checked, onCheckedChange, modifier, enabled = enabled,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedTrackColor = if (dark) Ios.greenDark else Color(0xFF34C759),
            checkedThumbColor = Color.White,
            checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            uncheckedThumbColor = Color.White,
            uncheckedBorderColor = Color.Transparent,
        ),
        thumbContent = null,
    )
}

/**
 * The top of a screen opened from another, as on iOS: "‹ Accounts" to go back and buttons on the
 * right, then the screen's title in large type.
 */
@Composable
fun BackBar(back: String, onBack: () -> Unit, title: String, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.TextButton(onClick = onBack, contentPadding = PaddingValues(start = 4.dp, end = 12.dp)) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowLeft, null,
                    modifier = Modifier.padding(end = 2.dp),
                )
                Text(back, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
        )
    }
}

/** A small rounded tag on the cell color; blue text when it can be tapped. */
@Composable
fun StatusChip(onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier = Modifier, tappable: Boolean = true) =
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        StatusChipBody(onClick, label, modifier, tappable)
    }

@Composable
private fun StatusChipBody(onClick: () -> Unit, label: @Composable () -> Unit, modifier: Modifier, tappable: Boolean) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = cellColor,
        contentColor = if (tappable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(vertical = 3.dp),
    ) {
        // Tall enough to tap on its own, so it doesn't need an invisible margin around it.
        Row(Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium) { label() }
        }
    }
}

/** "‹ Accounts": the iOS back link, for a screen whose layout already sets the title. */
@Composable
fun BackLink(back: String, onBack: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onBack, contentPadding = PaddingValues(start = 0.dp, end = 12.dp)) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, modifier = Modifier.padding(end = 2.dp))
        Text(back, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
