package com.fengsheng.gm

import com.fengsheng.Config
import java.net.URLDecoder
import java.util.function.Function

class setversion : Function<Map<String, String?>, String> {
    override fun apply(form: Map<String, String?>): String {
        return try {
            val name = URLDecoder.decode(form["version"]!!, Charsets.UTF_8)
            Config.ClientVersion.set(name.toInt())
            Config.save()
            "{\"result\": true}"
        } catch (e: NumberFormatException) {
            "{\"error\": \"参数错误\"}"
        } catch (e: NullPointerException) {
            "{\"error\": \"参数错误\"}"
        }
    }
}