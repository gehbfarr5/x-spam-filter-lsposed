package dev.xspamfilter.lsposed

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun overviewShowsHookHealthAndPrimaryNavigation() {
        composeRule.onNodeWithText("概览").assertIsDisplayed()
        composeRule.onNodeWithText("生效规则").assertIsDisplayed()
        composeRule.onNodeWithText("规则").assertIsDisplayed()
        composeRule.onNodeWithText("日志").assertIsDisplayed()
    }

    @Test
    fun sourcesShowsPresetCatalogAndSubscriptionEntry() {
        composeRule.onNodeWithText("来源").performClick()
        composeRule.onNodeWithText("订阅新来源").assertIsDisplayed()
        composeRule.onNodeWithText("ZPVIP 内置快照").assertIsDisplayed()
        composeRule.onNodeWithText("ZPVIP 在线词库").assertIsDisplayed()
        composeRule.onNodeWithText("x-comment-blocker 常规词库").assertIsDisplayed()
    }
}
