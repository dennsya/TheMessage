package com.fengsheng.card

import com.fengsheng.Game
import com.fengsheng.GameExecutor
import com.fengsheng.HumanPlayer
import com.fengsheng.Player
import com.fengsheng.phase.OnFinishResolveCard
import com.fengsheng.phase.OnSendCard
import com.fengsheng.phase.ResolveCard
import com.fengsheng.phase.SendPhaseStart
import com.fengsheng.protos.Common.*
import com.fengsheng.protos.Common.card_type.Yu_Qin_Gu_Zong
import com.fengsheng.protos.Common.color.Blue
import com.fengsheng.protos.Common.color.Red
import com.fengsheng.protos.Common.direction.*
import com.fengsheng.protos.Common.secret_task.*
import com.fengsheng.protos.Fengsheng.use_yu_qin_gu_zong_toc
import com.fengsheng.skill.SkillId
import com.fengsheng.skill.cannotPlayCard
import org.apache.logging.log4j.kotlin.logger
import java.util.concurrent.TimeUnit

class YuQinGuZong : Card {
    constructor(id: Int, colors: List<color>, direction: direction, lockable: Boolean) :
            super(id, colors, direction, lockable)

    constructor(id: Int, card: Card) : super(id, card)

    /**
     * 仅用于“作为欲擒故纵使用”
     */
    internal constructor(originCard: Card) : super(originCard)

    override val type = Yu_Qin_Gu_Zong

    override fun canUse(g: Game, r: Player, vararg args: Any): Boolean {
        if (r.cannotPlayCard(type)) {
            logger.error("你被禁止使用欲擒故纵")
            (r as? HumanPlayer)?.sendErrorMessage("你被禁止使用欲擒故纵")
            return false
        }
        if (r !== (g.fsm as? SendPhaseStart)?.whoseTurn) {
            logger.error("欲擒故纵的使用时机不对")
            (r as? HumanPlayer)?.sendErrorMessage("欲擒故纵的使用时机不对")
            return false
        }
        return true
    }

    @Suppress("UNCHECKED_CAST")
    override fun execute(g: Game, r: Player, vararg args: Any) {
        val fsm = g.fsm as SendPhaseStart
        val messageCard = args[0] as Card
        val dir = args[1] as direction
        val target = args[2] as Player
        val lockPlayers = args[3] as List<Player>
        logger.info("${r}使用了$this，选择了${messageCard}传递")
        r.deleteCard(id)
        val resolveFunc = { _: Boolean ->
            for (p in r.game!!.players) {
                if (p is HumanPlayer) {
                    val builder = use_yu_qin_gu_zong_toc.newBuilder()
                    builder.card = toPbCard()
                    builder.messageCardId = messageCard.id
                    builder.playerId = p.getAlternativeLocation(r.location)
                    builder.targetPlayerId = p.getAlternativeLocation(target.location)
                    lockPlayers.forEach { builder.addLockPlayerIds(p.getAlternativeLocation(it.location)) }
                    p.send(builder.build())
                }
            }
            r.deleteMessageCard(messageCard.id) // 欲擒故纵可能传出面前的情报
            r.draw(2)
            OnFinishResolveCard( // 这里先触发卡牌结算后，再触发情报传出时
                r, r, target, getOriginCard(), type, OnSendCard(
                    fsm.whoseTurn, fsm.whoseTurn, messageCard, dir, target, lockPlayers.toTypedArray(),
                    isMessageCardFaceUp = true, needRemoveCard = false, needNotify = false
                )
            )
        }
        g.resolve(ResolveCard(r, r, target, getOriginCard(), Yu_Qin_Gu_Zong, resolveFunc, fsm))
    }

    override fun toPbCard(): card {
        val builder = card.newBuilder()
        builder.cardId = id
        builder.cardDir = direction
        builder.canLock = lockable
        builder.cardType = type
        builder.addAllCardColor(colors)
        return builder.build()
    }

    override fun toString(): String {
        return "${cardColorToString(colors)}欲擒故纵"
    }

    companion object {
        fun ai(e: SendPhaseStart, card: Card): Boolean {
            val player = e.whoseTurn
            val game = player.game!!
            val players = game.players
            !player.cannotPlayCard(Yu_Qin_Gu_Zong) || return false
            var canRed = true
            var canBlue = true
            when (player.identity) {
                Red -> {
                    if (player.messageCards.count(Red) == 2)
                        canRed = false
                    if (players.any { p -> p !== player && p!!.alive && p.messageCards.count(Blue) == 2 })
                        canBlue = false
                }

                Blue -> {
                    if (player.messageCards.count(Blue) == 2)
                        canBlue = false
                    if (players.any { p -> p !== player && p!!.alive && p.messageCards.count(Red) == 2 })
                        canRed = false
                }

                else -> {
                    if (player.secretTask in listOf(Killer, Collector, Pioneer)) {
                        canRed = false
                        canBlue = false
                    }
                }
            }
            canRed || canBlue || return false
            val messageCard = player.messageCards.filter {
                (Red in it.colors || Blue in it.colors) && (canRed || Red !in it.colors) && (canBlue || Blue !in it.colors)
            }.randomOrNull() ?: return false
            val direction =
                if (player.findSkill(SkillId.LIAN_LUO) == null) messageCard.direction
                else arrayOf(Up, Left, Right).random()
            val target = when (direction) {
                Up -> players.filter { it!!.alive && it !== player }
                    .run { filter { it!!.isPartner(player) }.ifEmpty { this } }.randomOrNull()

                Left -> player.getNextLeftAlivePlayer().let { if (it !== player) it else null }
                Right -> player.getNextRightAlivePlayer().let { if (it !== player) it else null }
                else -> null
            } ?: return false
            GameExecutor.post(game, {
                card.asCard(Yu_Qin_Gu_Zong).execute(game, player, messageCard, direction, target, emptyList<Player>())
            }, 2, TimeUnit.SECONDS)
            return true
        }
    }
}