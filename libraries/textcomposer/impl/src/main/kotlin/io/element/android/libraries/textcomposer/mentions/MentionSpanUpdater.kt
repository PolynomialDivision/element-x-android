/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer.mentions

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import dev.zacsweers.metro.ContributesBinding
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.ui.messages.RoomMemberProfilesCache
import io.element.android.libraries.matrix.ui.messages.RoomNamesCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

interface MentionSpanUpdater {
    fun updateMentionSpans(text: CharSequence): CharSequence

    @Composable
    fun rememberMentionSpans(text: CharSequence): CharSequence
}

@ContributesBinding(RoomScope::class)
class DefaultMentionSpanUpdater(
    private val formatter: MentionSpanFormatter,
    private val theme: MentionSpanTheme,
    private val roomMemberProfilesCache: RoomMemberProfilesCache,
    private val roomNamesCache: RoomNamesCache,
) : MentionSpanUpdater {
    private val bitmapLoadCounter = MutableStateFlow(0)

    @Composable
    override fun rememberMentionSpans(text: CharSequence): CharSequence {
        val isLightTheme = ElementTheme.isLightTheme
        val roomInfoCacheUpdate by roomNamesCache.updateFlow.collectAsState(0)
        val roomMemberProfilesCacheUpdate by roomMemberProfilesCache.updateFlow.collectAsState(0)
        val bitmapVersion by bitmapLoadCounter.collectAsState()
        val context = LocalContext.current
        val result = remember(text, roomInfoCacheUpdate, roomMemberProfilesCacheUpdate, isLightTheme) {
            updateMentionSpans(text)
            text
        }
        @Suppress("UNUSED_EXPRESSION")
        bitmapVersion
        LaunchedEffect(text, roomMemberProfilesCacheUpdate) {
            val spans = text.getMentionSpans()
            for (span in spans) {
                if (span.type is MentionType.User) {
                    launch { loadAvatarBitmap(span, span.type.userId, context) }
                }
            }
        }
        return result
    }

    private suspend fun loadAvatarBitmap(span: MentionSpan, userId: UserId, context: Context) {
        if (span.avatarBitmap != null) return
        val avatarUrl = roomMemberProfilesCache.getAvatarUrl(userId) ?: return
        val displayName = roomMemberProfilesCache.getDisplayName(userId)
        val avatarData = AvatarData(
            id = userId.value,
            name = displayName,
            url = avatarUrl,
            size = AvatarSize.TimelineSender,
        )
        val imageLoader = SingletonImageLoader.get(context)
        val request = ImageRequest.Builder(context)
            .data(avatarData)
            .allowHardware(false)
            .build()
        val image = imageLoader.execute(request).image ?: return
        span.avatarBitmap = image.toBitmap()
        bitmapLoadCounter.value++
    }

    override fun updateMentionSpans(text: CharSequence): CharSequence {
        for (mentionSpan in text.getMentionSpans()) {
            mentionSpan.updateTheme(theme)
            mentionSpan.updateDisplayText(formatter)
        }
        return text
    }
}

private object NoOpMentionSpanUpdater : MentionSpanUpdater {
    override fun updateMentionSpans(text: CharSequence): CharSequence {
        return text
    }

    @Composable
    override fun rememberMentionSpans(text: CharSequence): CharSequence {
        return text
    }
}

val LocalMentionSpanUpdater = staticCompositionLocalOf<MentionSpanUpdater> { NoOpMentionSpanUpdater }
