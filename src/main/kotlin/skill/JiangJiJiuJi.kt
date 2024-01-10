package com.fengsheng.skill

import com.fengsheng.*
import com.fengsheng.phase.FightPhaseIdle
import com.fengsheng.protos.Common.card_type.Wu_Dao
import com.fengsheng.protos.Role.skill_jiang_ji_jiu_ji_toc
import org.apache.log4j.Logger

/**
 * 成年韩梅技能【将计就计】：你使用【误导】或者成为【误导】的目标之一时，可以将此角色牌翻回背面，摸一张牌。
 */
class JiangJiJiuJi : InitialSkill, TriggeredSkill {
    override val skillId = SkillId.JIANG_JI_JIU_JI

    override fun execute(g: Game, askWhom: Player): ResolveResult? {
        g.findEvent<UseCardEvent>(this) { event ->
            askWhom.alive || return@findEvent false
            event.cardType == Wu_Dao || return@findEvent false
            askWhom.roleFaceUp || return@findEvent false
            askWhom === event.player || askWhom === event.targetPlayer || askWhom === (event.currentFsm as? FightPhaseIdle)?.inFrontOfWhom
        } ?: return null
        askWhom.skills += JiangJiJiuJi2()
        return null
    }

    private class JiangJiJiuJi2 : TriggeredSkill, OneTurnSkill {
        override val skillId = SkillId.UNKNOWN

        override fun execute(g: Game, askWhom: Player): ResolveResult? {
            g.findEvent<FinishResolveCardEvent>(this) {
                askWhom.alive
            } ?: return null
            log.info("${askWhom}发动了[将计就计]")
            for (p in g.players) {
                if (p is HumanPlayer) {
                    val builder = skill_jiang_ji_jiu_ji_toc.newBuilder()
                    builder.playerId = p.getAlternativeLocation(askWhom.location)
                    p.send(builder.build())
                }
            }
            askWhom.draw(1)
            g.playerSetRoleFaceUp(askWhom, false)
            return null
        }

        companion object {
            private val log = Logger.getLogger(JiangJiJiuJi2::class.java)
        }
    }
}