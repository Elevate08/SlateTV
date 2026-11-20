package com.stasisstate.slatetv.connector.livetv

import android.content.Intent
import android.media.tv.TvContract
import org.junit.Assert.*
import org.junit.Test

class LiveTvChannelTunerTest {
    private val host = "com.slatetv.host"
    private val owner = "com.example.publisher"
    private val published = AppChannelIntentSnapshot(
        action = Intent.ACTION_VIEW,
        dataScheme = "example",
        dataAuthority = null,
        componentPackage = owner,
        targetPackage = null,
        flags = 0,
        hasSelector = false,
    )

    @Test fun systemTuneTriesBothLiveChannelsVariantsBeforeTheInputApp() {
        val channel = channelUriString(42)
        val raw = "example://watch/42"
        assertEquals(
            listOf(
                SystemTuneStep.View(IntentSpec(action = Intent.ACTION_VIEW, data = channel)),
                SystemTuneStep.View(IntentSpec(Intent.ACTION_VIEW, channel, TvContract.Channels.CONTENT_ITEM_TYPE, "com.google.android.tv")),
                SystemTuneStep.View(IntentSpec(Intent.ACTION_VIEW, channel, TvContract.Channels.CONTENT_ITEM_TYPE, "com.android.tv")),
                SystemTuneStep.AppLink(raw, owner),
                SystemTuneStep.LaunchPackage(owner),
            ),
            systemTuneSteps(channel, owner, raw, host),
        )
    }

    @Test fun systemTuneNeverFallsBackToSlateTvOrAnInvalidInputPackage() {
        val channel = channelUriString(42)
        for (input in listOf(null, host, "../publisher")) {
            val steps = systemTuneSteps(channel, input, "example://watch/42", host)
            assertEquals(3, steps.size)
            assertTrue(steps.all { it is SystemTuneStep.View })
        }
        assertEquals(SystemTuneStep.LaunchPackage(owner), systemTuneSteps(channel, owner, "", host).last())
    }

    @Test fun trustedProgramRowCanDeepLinkOrFallBackToItsPublisher() {
        val raw = "example://watch/42"
        assertEquals(ProgramLaunchPlan.IntentUri(raw, owner), planProgramLaunch(ProgramLaunchRow(owner, raw), owner, host))
        for (unusable in listOf(null, " ", "x".repeat(MAX_APP_INTENT_URI_LENGTH + 1))) {
            assertEquals(ProgramLaunchPlan.OwnerLauncher(owner), planProgramLaunch(ProgramLaunchRow(owner, unusable), owner, host))
        }
    }

    @Test fun programLaunchRequiresAReadBackRowOwnedByTheExpectedExternalApp() {
        assertEquals(ProgramLaunchPlan.Reject, planProgramLaunch(null, owner, host))
        assertEquals(ProgramLaunchPlan.Reject, planProgramLaunch(ProgramLaunchRow(null, "example://watch"), owner, host))
        assertEquals(ProgramLaunchPlan.Reject, planProgramLaunch(ProgramLaunchRow("com.other.app", null), owner, host))
        assertEquals(ProgramLaunchPlan.Reject, planProgramLaunch(ProgramLaunchRow(host, null), host, host))
        assertEquals(ProgramLaunchPlan.Reject, planProgramLaunch(ProgramLaunchRow("../invalid", null), "../invalid", host))
    }

    @Test fun intentValidationStripsUriGrantsAndSelectorAndPinsTheActivityToItsOwner() {
        val raw = published.copy(flags = URI_GRANT_FLAGS or Intent.FLAG_ACTIVITY_CLEAR_TOP, hasSelector = true)
        val accepted = validateAppChannelIntent(raw, owner, host) as AppChannelIntentVerdict.Accepted
        assertEquals(
            published.copy(flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK, targetPackage = owner),
            accepted.sanitized,
        )
    }

    @Test fun intentValidationRejectsForeignTargetsIncludingTheHostApp() {
        for (target in listOf(host, "com.other.app")) {
            assertTrue(validateAppChannelIntent(published.copy(componentPackage = target), owner, host) is AppChannelIntentVerdict.Rejected)
            assertTrue(validateAppChannelIntent(published.copy(targetPackage = target), owner, host) is AppChannelIntentVerdict.Rejected)
        }
        assertTrue(validateAppChannelIntent(published, host, host) is AppChannelIntentVerdict.Rejected)
    }

    @Test fun intentValidationAllowsPublisherActionsAndRejectsDangerousActionsAndSchemes() {
        for (action in listOf(Intent.ACTION_MAIN, Intent.ACTION_VIEW, "$owner.PLAY")) {
            assertTrue(validateAppChannelIntent(published.copy(action = action), owner, host) is AppChannelIntentVerdict.Accepted)
        }
        for (action in listOf(Intent.ACTION_CALL, Intent.ACTION_SEND, Intent.ACTION_DELETE)) {
            assertTrue(validateAppChannelIntent(published.copy(action = action), owner, host) is AppChannelIntentVerdict.Rejected)
        }
        for (scheme in listOf("file", "FILE", "javascript", "jar")) {
            assertTrue(validateAppChannelIntent(published.copy(dataScheme = scheme), owner, host) is AppChannelIntentVerdict.Rejected)
        }
        assertTrue(
            validateAppChannelIntent(published.copy(dataScheme = "content", dataAuthority = "10@$host.files"), owner, host)
                is AppChannelIntentVerdict.Rejected,
        )
    }

    @Test fun unsafeIntentExtrasIncludeStreamAndNestedIntents() {
        assertTrue(Intent.EXTRA_STREAM in UNSAFE_INTENT_EXTRAS)
        assertTrue(Intent.EXTRA_INTENT in UNSAFE_INTENT_EXTRAS)
        assertTrue(Intent.EXTRA_ALTERNATE_INTENTS in UNSAFE_INTENT_EXTRAS)
        assertTrue(Intent.EXTRA_INITIAL_INTENTS in UNSAFE_INTENT_EXTRAS)
    }
}
