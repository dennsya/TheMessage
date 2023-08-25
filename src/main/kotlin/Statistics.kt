package com.fengsheng

import com.fengsheng.ScoreFactory.addScore
import com.fengsheng.protos.Common.*
import com.fengsheng.protos.Fengsheng.get_record_list_toc
import com.fengsheng.skill.RoleCache
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.apache.log4j.Logger
import java.io.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.fixedRateTimer
import kotlin.random.Random

object Statistics {
    private val pool = Channel<() -> Unit>(Channel.UNLIMITED)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    private val playerInfoMap = ConcurrentHashMap<String, PlayerInfo>()
    private val totalWinCount = AtomicInteger()
    private val totalGameCount = AtomicInteger()
    private val trialStartTime = ConcurrentHashMap<String, Long>()

    init {
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch {
            while (true) {
                val f = pool.receive()
                withContext(Dispatchers.IO) { f() }
            }
        }

        fixedRateTimer(daemon = true, period = 24 * 3600 * 1000) {
            val file = File("playerInfo.csv")
            if (file.exists()) file.copyTo(File("playerInfo.csv.bak"), true)
        }
    }

    fun add(records: List<Record>) {
        ScoreFactory.addWinCount(records)
        pool.trySend {
            try {
                val time = dateFormat.format(Date())
                val sb = StringBuilder()
                for (r in records) {
                    sb.append(r.role).append(',')
                    sb.append(r.isWinner).append(',')
                    sb.append(r.identity).append(',')
                    sb.append(if (r.identity == color.Black) r.task.toString() else "").append(',')
                    sb.append(r.totalPlayerCount).append(',')
                    sb.append(time).append('\n')
                }
                writeFile("stat.csv", sb.toString().toByteArray(), true)
            } catch (e: Exception) {
                log.error("execute task failed", e)
            }
        }
    }

    fun addPlayerGameCount(playerGameResultList: List<PlayerGameResult>) {
        pool.trySend {
            try {
                var win = 0
                var game = 0
                var updateTrial = false
                for (count in playerGameResultList) {
                    if (count.isWin) {
                        win++
                        if (trialStartTime.remove(count.device) != null) updateTrial = true
                    }
                    game++
                    playerInfoMap.computeIfPresent(count.playerName) { _, v ->
                        val addWin = if (count.isWin) 1 else 0
                        v.copy(winCount = v.winCount + addWin, gameCount = v.gameCount + 1)
                    }
                }
                totalWinCount.addAndGet(win)
                totalGameCount.addAndGet(game)
                savePlayerInfo()
                if (updateTrial) saveTrials()
            } catch (e: Exception) {
                log.error("execute task failed", e)
            }
        }
    }

    fun register(name: String): Boolean {
        val result = playerInfoMap.putIfAbsent(name, PlayerInfo(name, 0, "", 0, 0)) == null
        if (result) pool.trySend(::savePlayerInfo)
        return result
    }

    fun login(name: String, pwd: String?): PlayerInfo? {
        val password = try {
            if (pwd.isNullOrEmpty()) "" else md5(name + pwd)
        } catch (e: NoSuchAlgorithmException) {
            log.error("md5加密失败", e)
            return null
        }
        var changed = false
        val playerInfo = playerInfoMap.computeIfPresent(name) { _, v ->
            if (v.password.isEmpty() && password.isNotEmpty()) {
                changed = true
                v.copy(password = password)
            } else v
        } ?: return null
        if (changed) pool.trySend(::savePlayerInfo)
        if (password != playerInfo.password) return null
        return playerInfo
    }

    fun getPlayerInfo(name: String) = playerInfoMap[name]
    fun getScore(name: String) = playerInfoMap[name]?.score

    /**
     * @return Pair(score的新值, score的变化量)
     */
    fun updateScore(name: String, score: Int, save: Boolean): Pair<Int, Int> {
        var newScore = 0
        var delta = 0
        playerInfoMap.computeIfPresent(name) { _, v ->
            newScore = v.score addScore score
            delta = newScore - v.score
            v.copy(score = newScore)
        }
        if (save) pool.trySend(::savePlayerInfo)
        return newScore to delta
    }

    fun getAllPlayerInfo() = playerInfoMap.map { (_, v) -> v }

    fun resetPassword(name: String): Boolean {
        if (playerInfoMap.computeIfPresent(name) { _, v -> v.copy(password = "") } != null) {
            pool.trySend(::savePlayerInfo)
            return true
        }
        return false
    }

    fun getPlayerGameCount(name: String): PlayerGameCount {
        val playerInfo = playerInfoMap[name] ?: return PlayerGameCount(0, 0)
        return PlayerGameCount(playerInfo.winCount, playerInfo.gameCount)
    }

    val totalPlayerGameCount: PlayerGameCount
        get() = PlayerGameCount(totalWinCount.get(), totalGameCount.get())

    private fun savePlayerInfo() {
        val sb = StringBuilder()
        for ((_, info) in playerInfoMap) {
            sb.append(info.winCount).append(',')
            sb.append(info.gameCount).append(',')
            sb.append(info.name).append(',')
            sb.append(info.score).append(',')
            sb.append(info.password).append('\n')
        }
        writeFile("playerInfo.csv", sb.toString().toByteArray())
    }

    private fun saveTrials() {
        val sb = StringBuilder()
        for ((key, value) in trialStartTime) {
            sb.append(value).append(',')
            sb.append(key).append('\n')
        }
        writeFile("trial.csv", sb.toString().toByteArray())
    }

    @Throws(IOException::class)
    fun load() {
        var winCount = 0
        var gameCount = 0
        try {
            BufferedReader(InputStreamReader(FileInputStream("playerInfo.csv"))).use { reader ->
                var line: String?
                while (true) {
                    line = reader.readLine()
                    if (line == null) break
                    val a = line.split(",".toRegex(), limit = 5).toTypedArray()
                    val password = a[4]
                    val score = if (a[3].length < 6) a[3].toInt() else 0 // 以前这个位置是deviceId
                    val name = a[2]
                    val win = a[0].toInt()
                    val game = a[1].toInt()
                    if (playerInfoMap.put(name, PlayerInfo(name, score, password, win, game)) != null)
                        throw RuntimeException("数据错误，有重复的玩家name")
                    winCount += win
                    gameCount += game
                }
            }
        } catch (ignored: FileNotFoundException) {
        }
        totalWinCount.set(winCount)
        totalGameCount.set(gameCount)
        try {
            BufferedReader(InputStreamReader(FileInputStream("trial.csv"))).use { reader ->
                var line: String?
                while (true) {
                    line = reader.readLine()
                    if (line == null) break
                    val a = line!!.split(",".toRegex(), limit = 2).toTypedArray()
                    trialStartTime[a[1]] = a[0].toLong()
                }
            }
        } catch (ignored: FileNotFoundException) {
        }
    }

    fun getTrialStartTime(deviceId: String): Long {
        return trialStartTime.getOrDefault(deviceId, 0L)
    }

    fun setTrialStartTime(device: String, time: Long) {
        pool.trySend {
            try {
                trialStartTime[device] = time
                saveTrials()
            } catch (e: Exception) {
                log.error("execute task failed", e)
            }
        }
    }

    fun displayRecordList(player: HumanPlayer) {
        pool.trySend {
            val builder = get_record_list_toc.newBuilder()
            val dir = File("records")
            val files = dir.list()
            if (files != null) {
                files.sort()
                var lastPrefix: String? = null
                var j = 0
                for (i in files.indices.reversed()) {
                    if (files[i].length < 19) continue
                    if (lastPrefix == null || !files[i].startsWith(lastPrefix)) {
                        if (++j > Config.RecordListSize) break
                        lastPrefix = files[i].substring(0, 19)
                    }
                    builder.addRecords(files[i])
                }
            }
            player.send(builder.build())
        }
    }

    class Record(
        val role: role,
        val isWinner: Boolean,
        val identity: color,
        val task: secret_task,
        val totalPlayerCount: Int
    )

    class PlayerGameResult(val device: String, val playerName: String, val isWin: Boolean)

    data class PlayerGameCount(val winCount: Int, val gameCount: Int) {
        fun random(): PlayerGameCount {
            val i = Random.nextInt(20)
            return PlayerGameCount(winCount * i / 100, gameCount * i / 100)
        }

        fun inc(isWinner: Boolean) = PlayerGameCount(winCount + if (isWinner) 1 else 0, gameCount + 1)

        val rate get() = if (gameCount == 0) 0.0 else winCount * 100.0 / gameCount
    }

    data class PlayerInfo(
        val name: String,
        val score: Int,
        val password: String,
        val winCount: Int,
        val gameCount: Int
    )

    init {
        dateFormat.timeZone = TimeZone.getTimeZone("GMT+8:00")
    }

    private val log = Logger.getLogger(Statistics::class.java)

    private fun writeFile(fileName: String, buf: ByteArray, append: Boolean = false) {
        try {
            FileOutputStream(fileName, append).use { fileOutputStream -> fileOutputStream.write(buf) }
        } catch (e: IOException) {
            log.error("write file failed", e)
        }
    }

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        fun IntArray.inc(index: Int? = null) {
            this[0]++
            if (index != null) {
                this[2]++
                this[index]++
            } else {
                this[1]++
            }
        }

        fun <K> HashMap<K, IntArray>.sum(index: Int): Int {
            var sum = 0
            this.forEach { sum += it.value[index] }
            return sum
        }

        val playerCountAppearCount = TreeMap<Int, IntArray>()
        val playerCountWinCount = TreeMap<Int, IntArray>()
        val appearCount = HashMap<role, IntArray>()
        val winCount = HashMap<role, IntArray>()
        FileInputStream("stat.csv").use { `is` ->
            BufferedReader(InputStreamReader(`is`)).use { reader ->
                var line: String?
                while (true) {
                    line = reader.readLine()
                    if (line == null) break
                    val a = line.split(Regex(",")).dropLastWhile { it.isEmpty() }.toTypedArray()
                    val role = role.valueOf(a[0])
                    val playerCount = a[4].toInt()
                    val playerCountAppear = playerCountAppearCount.computeIfAbsent(playerCount) { IntArray(10) }
                    val playerCountWin = playerCountWinCount.computeIfAbsent(playerCount) { IntArray(10) }
                    val appear = appearCount.computeIfAbsent(role) { IntArray(10) }
                    val win = winCount.computeIfAbsent(role) { IntArray(10) }
                    val index =
                        if ("Black" == a[2]) secret_task.valueOf(a[3]).number + 3
                        else null
                    appear.inc(index)
                    playerCountAppear.inc(index)
                    if (a[1].toBoolean()) {
                        win.inc(index)
                        playerCountWin.inc(index)
                    }
                }
            }
        }
        val lines = ArrayList<Pair<Double, String>>()
        for ((key, value) in appearCount) {
            val sb = StringBuilder()
            val roleName = RoleCache.getRoleName(key) ?: ""
            sb.append(roleName)
            sb.append(",")
            sb.append(value[0])
            var winRate: Double? = null
            for (i in 0 until 9) { // 不显示清道夫，所以这里只有9
                val v = value[i]
                sb.append(",")
                winCount[key]?.let { if (v == 0) null else it[i] * 100.0 / v }?.let { r ->
                    if (winRate == null) winRate = r
                    sb.append("%.2f%%".format(r))
                }
            }
            lines.add(winRate!! to sb.toString())
        }
        lines.sortByDescending { it.first }
        val playerCountLines = ArrayList<String>()
        for ((key, value) in playerCountAppearCount) {
            val sb = StringBuilder()
            sb.append("${key}人局")
            sb.append(",")
            sb.append(value[0] / key)
            for (i in 0 until 9) { // 不显示清道夫，所以这里只有9
                val v = value[i]
                sb.append(",")
                playerCountWinCount[key]?.let { if (v == 0) null else it[i] * 100.0 / v }?.let { r ->
                    sb.append("%.2f%%".format(r))
                }
            }
            playerCountLines.add(sb.toString())
        }
        val playerAppearCountLines = ArrayList<String>()
        for ((key, value) in playerCountAppearCount) {
            val sb = StringBuilder()
            sb.append("${key}人局")
            sb.append(",")
            sb.append(value[0] / key)
            for (i in 0 until 9) // 不显示清道夫，所以这里只有9
                sb.append(",${value[i]}")
            playerAppearCountLines.add(sb.toString())
        }
        FileOutputStream("stat0.csv").use { os ->
            BufferedWriter(OutputStreamWriter(os)).use { writer ->
                writer.write("角色,场次,胜率,军潜胜率,神秘人胜率,镇压者胜率,簒夺者胜率,双重间谍胜率,诱变者胜率,先行者胜率,搅局者胜率")
                writer.newLine()
                writer.write("全部,${appearCount.sum(0)}")
                for (i in 0 until 9) { // 不显示清道夫，所以这里只有9
                    writer.write(",")
                    val winSum = winCount.sum(i)
                    val appearSum = appearCount.sum(i)
                    if (appearSum != 0) writer.write("%.2f%%".format(winSum * 100.0 / appearSum))
                }
                writer.newLine()
                for (line in lines) {
                    writer.write(line.second)
                    writer.newLine()
                }
                writer.newLine()
                writer.write("人数,场次,人均胜率,军潜胜率,神秘人胜率,镇压者胜率,簒夺者胜率,双重间谍胜率,诱变者胜率,先行者胜率,搅局者胜率")
                writer.newLine()
                for (line in playerCountLines) {
                    writer.write(line)
                    writer.newLine()
                }
                writer.newLine()
                writer.write("人数,场次,总出现次数,军潜出现次数,神秘人出现次数,镇压者出现次数,簒夺者出现次数,双重间谍出现次数,诱变者出现次数,先行者出现次数,搅局者出现次数")
                writer.newLine()
                for (line in playerAppearCountLines) {
                    writer.write(line)
                    writer.newLine()
                }
            }
        }
    }

    private val hexDigests =
        charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')

    @Throws(NoSuchAlgorithmException::class)
    private fun md5(s: String): String {
        try {
            val `in` = s.toByteArray(StandardCharsets.UTF_8)
            val messageDigest = MessageDigest.getInstance("md5")
            messageDigest.update(`in`)
            // 获得密文
            val md = messageDigest.digest()
            // 将密文转换成16进制字符串形式
            val j = md.size
            val str = CharArray(j * 2)
            var k = 0
            for (b in md) {
                str[k++] = hexDigests[b.toInt() ushr 4 and 0xf] // 高4位
                str[k++] = hexDigests[b.toInt() and 0xf] // 低4位
            }
            return String(str)
        } catch (e: NoSuchAlgorithmException) {
            log.warn("calculate md5 failed: ", e)
            return s
        }
    }
}