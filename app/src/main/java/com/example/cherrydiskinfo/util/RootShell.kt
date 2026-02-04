package com.example.cherrydiskinfo.util

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Root Shell 工具类
 * 用于执行需要 Root 权限的命令
 */
object RootShell {
    
    private const val TAG = "RootShell"
    
    /**
     * 执行 Root 命令
     * @param command 要执行的命令
     * @return 命令输出结果，失败返回空字符串
     */
    fun execute(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec("su -c $command")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            
            reader.close()
            process.waitFor()
            
            output.toString().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command: $command", e)
            ""
        }
    }
    
    /**
     * 执行多条 Root 命令
     * @param commands 命令列表
     * @return 每条命令的输出结果
     */
    fun executeMultiple(vararg commands: String): List<String> {
        return commands.map { execute(it) }
    }
    
    /**
     * 检查文件是否存在
     */
    fun fileExists(path: String): Boolean {
        return execute("[ -f $path ] && echo 'yes' || echo 'no'") == "yes"
    }
    
    /**
     * 读取文件内容
     */
    fun readFile(path: String): String {
        return if (fileExists(path)) {
            execute("cat $path")
        } else {
            ""
        }
    }
    
    /**
     * 列出目录内容
     */
    fun listDir(path: String): List<String> {
        val output = execute("ls -1 $path 2>/dev/null")
        return if (output.isNotEmpty()) output.split("\n") else emptyList()
    }
    
    /**
     * 查找文件路径
     */
    fun findPath(pattern: String): List<String> {
        val output = execute("find $pattern -type f 2>/dev/null | head -20")
        return if (output.isNotEmpty()) output.split("\n") else emptyList()
    }
}
