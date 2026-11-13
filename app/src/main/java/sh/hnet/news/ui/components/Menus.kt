@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package sh.hnet.news.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** An item of a DropdownMenuGroup, as Music's: icon and one line; [error] for the destructive one. [index] of [count] in its group gives the shape. */
@Composable
fun MenuItem(text: String, @DrawableRes icon: Int, index: Int, count: Int, error: Boolean = false, onClick: () -> Unit) {
    val errorColor = MaterialTheme.colorScheme.error
    DropdownMenuItem(
        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (error) errorColor else Color.Unspecified) },
        leadingIcon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp), tint = if (error) errorColor else LocalContentColor.current) },
        shape = MenuDefaults.itemShape(index, count).shape,
        onClick = onClick,
    )
}
