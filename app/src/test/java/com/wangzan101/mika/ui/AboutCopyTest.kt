package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.ResFileTexts
import com.wangzan101.mika.i18n.UiText
import com.wangzan101.mika.about.CheckStatus
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.about.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val r020 = ReleaseInfo("0.2.0", "Mika 0.2.0", "- 新", "2026-11-05", "https://github.com/wangzan101/Mika/releases/tag/v0.2.0", null)
    private val r010 = r020.copy(version = "0.1.0")

    @Test
    fun checkRowStates() {
        assertEquals(AboutCopy.CheckValue(null), AboutCopy.checkValue(UpdateState()))
        assertTrue(AboutCopy.checkValue(UpdateState(status = CheckStatus.Checking)).spinner)
        // 连不上但早先查到过新版:照样提示有新版
        assertTrue(AboutCopy.checkValue(UpdateState(latest = r020, update = r020, status = CheckStatus.Failed)).dot)
    }

    /** 联系作者面板:中文界面微信码在前;英文界面 X 群聊在前。 */
    @Test
    fun xGroupFirstOnlyInEnglish() {
        assertFalse(AboutCopy.X_GROUP_FIRST)
        UiText.source = ResFileTexts.traditional
        assertFalse(AboutCopy.X_GROUP_FIRST)
        UiText.source = ResFileTexts.english
        assertTrue(AboutCopy.X_GROUP_FIRST)
    }
}
