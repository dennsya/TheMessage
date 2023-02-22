package com.fengsheng.handler

import com.fengsheng.HumanPlayer
import com.fengsheng.protos.Role
import com.fengsheng.skill.Skill
import com.fengsheng.skill.SkillId
import org.apache.log4j.Logger
import java.util.*

class skill_ji_ban_b_tos : AbstractProtoHandler<Role.skill_ji_ban_b_tos>() {
    override fun handle0(r: HumanPlayer, pb: Role.skill_ji_ban_b_tos) {
        val skill = r.findSkill<Skill>(SkillId.JI_BAN)
        if (skill == null) {
            log.error("你没有这个技能")
            return
        }
        if (HashSet(pb.cardIdsList).size != pb.cardIdsCount) {
            log.error("卡牌重复" + Arrays.toString(pb.cardIdsList.toTypedArray()))
            return
        }
        r.game.tryContinueResolveProtocol(r, pb)
    }

    companion object {
        private val log = Logger.getLogger(skill_ji_ban_b_tos::class.java)
    }
}