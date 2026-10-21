package me.rerere.awara.ui.component.iwara.comment

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.awara.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 使用本地不存在的文件模型检查加载入口，不向真实图床发请求。 */
@RunWith(AndroidJUnit4::class)
class CommentImageLoadingTest {
    @get:Rule val compose = createComposeRule()
    private val image = CommentBodySegment.Image("测试图片", "file:///missing-comment-image.png")
    private val loadLabel
        get() =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .getString(R.string.comment_image_load)

    @Test
    fun disabledAutoLoadDoesNotCreateImageUntilUserClicks() {
        compose.setContent {
            MaterialTheme { CommentEmbeddedImage(image, autoLoad = false, onClick = {}) }
        }
        compose.onNodeWithText(loadLabel).assertExists()
        compose.onNodeWithContentDescription("测试图片").assertDoesNotExist()
        compose.onNodeWithText(loadLabel).performClick()
        compose.onNodeWithContentDescription("测试图片").assertExists()
        compose.onNodeWithText(loadLabel).assertDoesNotExist()
    }

    @Test
    fun enablingThenDisablingAutoLoadDoesNotGrantManualPermission() {
        var autoLoad by mutableStateOf(true)
        compose.setContent { MaterialTheme { CommentEmbeddedImage(image, autoLoad, onClick = {}) } }
        compose.onNodeWithContentDescription("测试图片").assertExists()
        compose.runOnIdle { autoLoad = false }
        compose.onNodeWithContentDescription("测试图片").assertDoesNotExist()
        compose.onNodeWithText(loadLabel).assertExists()
    }

    @Test
    fun explicitPermissionDoesNotApplyToAnotherImageUrl() {
        var currentImage by mutableStateOf(image)
        compose.setContent {
            MaterialTheme { CommentEmbeddedImage(currentImage, autoLoad = false, onClick = {}) }
        }
        compose.onNodeWithText(loadLabel).performClick()
        compose.onNodeWithContentDescription("测试图片").assertExists()
        compose.runOnIdle { currentImage = image.copy(url = "file:///another-missing-image.png") }
        compose.onNodeWithContentDescription("测试图片").assertDoesNotExist()
        compose.onNodeWithText(loadLabel).assertExists()
    }
}
