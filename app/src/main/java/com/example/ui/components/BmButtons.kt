package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.CoralContainer
import com.example.ui.theme.CoralDanger
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.GreyOutline
import com.example.ui.theme.GreySurfaceVariant
import com.example.ui.theme.InkPrimary

enum class BmButtonKind { GO, STOP, SOFT }

/**
 * BetManager button: flat, soft tinted fill, thin border in a darker tone of the same colour, 14dp corners,
 * no elevation, no gradient, always at least 52dp tall so it is easy to hit.
 */
@Composable
fun BmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BmButtonKind = BmButtonKind.SOFT,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val (fill, content, border) = when (kind) {
        BmButtonKind.GO -> Triple(EmeraldContainer, EmeraldDeep, EmeraldDeep.copy(alpha = 0.55f))
        BmButtonKind.STOP -> Triple(CoralContainer, CoralDanger, CoralDanger.copy(alpha = 0.55f))
        BmButtonKind.SOFT -> Triple(GreySurfaceVariant, InkPrimary, GreyOutline)
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        border = BorderStroke(1.dp, if (enabled) border else GreyOutline),
        colors = ButtonDefaults.buttonColors(
            containerColor = fill,
            contentColor = content,
            disabledContainerColor = GreySurfaceVariant.copy(alpha = 0.6f),
            disabledContentColor = InkPrimary.copy(alpha = 0.4f)
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        modifier = modifier.defaultMinSize(minHeight = 52.dp).height(52.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text = text, fontWeight = FontWeight.SemiBold)
        }
    }
}
