package com.fengsheng.skill

import com.fengsheng.*
import com.fengsheng.protos.Common.card_type.Diao_Bao
import com.fengsheng.protos.Common.card_type.Po_Yi
import com.fengsheng.protos.Role.skill_huan_ri_toc
import org.apache.log4j.Logger

/**
 * 鄭文先技能【换日】：你使用【调包】或【破译】后，可以将你的角色牌翻至面朝下。
 */
class HuanRi : InitialSkill, TriggeredSkill {
    override val skillId = SkillId.HUAN_RI

    override fun execute(g: Game, askWhom: Player): ResolveResult? {
        g.findEvent<FinishResolveCardEvent>(this) { event ->
            askWhom === event.player || return@findEvent false
            askWhom.alive || return@findEvent false
            event.cardType == Diao_Bao || event.cardType == Po_Yi || return@findEvent false
            event.player.roleFaceUp
        } ?: return null
        log.info("${askWhom}发动了[换日]")
        for (p in g.players) {
            if (p is HumanPlayer) {
                val builder = skill_huan_ri_toc.newBuilder()
                builder.playerId = p.getAlternativeLocation(askWhom.location)
                p.send(builder.build())
            }
        }
        g.playerSetRoleFaceUp(askWhom, false)
        return null
    }

    companion object {
        private val log = Logger.getLogger(HuanRi::class.java)
    }
}