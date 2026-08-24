package com.zhousl.aether.ui.pet

import com.zhousl.aether.data.AppAccent
import com.zhousl.aether.data.AppThemeMode

/**
 * Built-in pet registry. Each pet carries its sprite atlas path plus a
 * recommended accent/theme that the personalization page applies on selection
 * (individual settings can still be changed afterwards).
 */
data class PetDefinition(
    val id: String,
    val displayName: String,
    val description: String,
    val spriteAssetPath: String,
    val recommendedAccent: AppAccent,
    val recommendedThemeMode: AppThemeMode,
    val personalityPrompt: String,
)

object QingPetCatalog {
    const val DefaultPetId = "qing-cat"

    val pets: List<PetDefinition> = listOf(
        PetDefinition(
            id = "qing-cat",
            displayName = "小青",
            description = "青 Qing 的吉祥物：一只青蓝色小猫，白肚皮白嘴，卷尾带白尖。",
            spriteAssetPath = "pet/qing-cat/spritesheet.webp",
            recommendedAccent = AppAccent.Teal,
            recommendedThemeMode = AppThemeMode.System,
            personalityPrompt = "你的伙伴是一只青蓝色小猫「小青」。保持专业、务实、简洁的语气正常完成任务，不需要模仿宠物说话或卖萌。",
        ),
        PetDefinition(
            id = "qing-frog",
            displayName = "奶蛙",
            description = "青 Qing 的第二只吉祥物：一只奶黄色圆滚滚的小青蛙，白肚皮，绿色大眼睛，棕色蹼状手脚，3D 治愈系风格。",
            spriteAssetPath = "pet/qing-frog/spritesheet.webp",
            recommendedAccent = AppAccent.Warm,
            recommendedThemeMode = AppThemeMode.Light,
            personalityPrompt = "你的伙伴是一只奶黄色的小青蛙「奶蛙」，性格搞怪无厘头。完成任务时内容保持专业准确，但语气可以更皮、更有梗：偶尔玩梗、用点俏皮话或夸张的总结，比如“区区小事，呱～搞定”。",
        ),
    )

    fun byId(id: String?): PetDefinition =
        pets.firstOrNull { it.id == id } ?: pets.first()
}
