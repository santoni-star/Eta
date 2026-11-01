package io.github.mangi.eta.ui.pages.providers

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.providerBrandLogoRes as sharedProviderBrandLogoRes
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Стандартна комбінація назви групи + картки однаково використовується на сторінках, пов’язаних із постачальником.*/
@Composable
internal fun ProviderSection(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        if (title != null) {
            EtaPreferenceGroupTitle(title)
        }
        EtaPreferenceGroup(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = EtaPreferenceDefaults.SidePadding)
                .padding(bottom = EtaPreferenceDefaults.GroupSpacing),
        ) {
            content()
        }
    }
}

/** Оригінальний кольоровий значок виробника; ресурс уже містить фонову та безпечну область, придатну для кругового кадрування.*/
@Composable
internal fun ProviderBrandIcon(
    sourceType: String,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(sourceType) ?: return
    ProviderBrandImage(logo = logo, modifier = modifier)
}

@Composable
private fun ProviderBrandImage(
    @DrawableRes logo: Int,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(logo),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape),
    )
}

@DrawableRes
internal fun providerBrandLogoRes(provider: ProviderSetting): Int? =
    sharedProviderBrandLogoRes(provider)

@DrawableRes
internal fun providerBrandLogoRes(sourceType: String): Int? =
    sharedProviderBrandLogoRes(sourceType)

/** Відомі постачальники використовують фірмові значки, невідомі джерела продовжують використовувати загальні значки за типом протоколу.*/
@Composable
internal fun ProviderIcon(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
) {
    val logo = providerBrandLogoRes(provider)
    if (logo != null) {
        ProviderBrandImage(logo = logo, modifier = modifier)
        return
    }

    when (provider) {
        is CustomProviderSetting -> EtaPreferenceIcon(
            icon = Icons.Rounded.Dns,
            modifier = modifier,
            tint = EtaPreferenceColors.Blue,
        )
        else -> EtaPreferenceIcon(
            icon = Icons.Rounded.Language,
            modifier = modifier,
            tint = EtaPreferenceColors.Blue,
        )
    }
}

internal enum class TagChipTone { Normal, Emphasized }

/** Маленькі теги капсули, які використовуються для тегів здібностей і тегів статусу.*/
@Composable
internal fun TagChip(
    text: String,
    tone: TagChipTone = TagChipTone.Normal,
) {
    val background: Color
    val foreground: Color
    when (tone) {
        TagChipTone.Normal -> {
            background = MiuixTheme.colorScheme.secondaryContainer
            foreground = MiuixTheme.colorScheme.onSecondaryContainer
        }
        TagChipTone.Emphasized -> {
            background = MiuixTheme.colorScheme.primaryContainer
            foreground = MiuixTheme.colorScheme.onPrimaryContainer
        }
    }
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        color = foreground,
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
