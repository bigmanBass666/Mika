package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.chat.ConversationSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

/**
 * 一个对话的工作区占了多少:[dirName] 是它在 `work/` 下的目录名;[conversationId]、[title] 是它所属的对话
 * (对话已经删掉、认不出来时为 null);[busy] 为 true 时这个对话有任务在跑或排队,不能清。
 */
data class WorkspaceUsage(val dirName: String, val conversationId: String?, val title: String?, val bytes: Long, val busy: Boolean)

/** 文件与计算环境占的空间:运行环境、技能、各对话的工作区(大的在前)。 */
data class SandboxUsage(val runtime: Long, val skills: Long, val workspaces: List<WorkspaceUsage>) {
    val workspacesTotal: Long get() = workspaces.sumOf { it.bytes }
    val total: Long get() = runtime + skills + workspacesTotal
}

/**
 * 读、清各对话的工作区。大小按目录树里的普通文件算,不跟进链接(工作区里的命令能建链接,指到哪都不算进来);
 * 清的时候和删对话一样经文件闸门按句柄删、不跟进链接,拿着树锁的共享许可、和重置互斥;
 * 这个对话有任务在跑或排队时不清([active]),免得删掉任务正在用的文件。运行环境和技能随 App 自带,不在这里清。
 */
class SandboxUsageReader(
    private val dirs: SandboxDirs,
    /** 存着的对话(编号和标题)。 */
    private val conversations: () -> List<ConversationSummary>,
    /** 有任务在跑或排队的对话。 */
    private val active: () -> Collection<String>,
) {
    /** 读一遍磁盘;工作区可能有很多文件,要在后台线程调。 */
    fun read(): SandboxUsage {
        val byDir = conversations().associateBy { dirs.workspace(it.id).name }
        val busy = active().map { dirs.workspace(it).name }.toSet()
        val workspaces = dirs.work.listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) && it.isDirectory }.map { dir ->
            val c = byDir[dir.name]
            WorkspaceUsage(dir.name, c?.id, c?.title, AndroidSandbox.treeBytes(dir.toPath()), dir.name in busy)
        }.sortedByDescending { it.bytes }
        return SandboxUsage(AndroidSandbox.treeBytes(dirs.rootfs.toPath()), AndroidSandbox.treeBytes(dirs.skills.toPath()), workspaces)
    }

    /** 清掉 `work/` 下名为 [dirName] 的工作区;它的对话有任务在跑时不清,返回 false。 */
    suspend fun clear(dirName: String): Boolean {
        if (dirName in busyDirs()) return false
        dirs.tree.shared { withContext(Dispatchers.IO) { dirs.gate.deleteTree(dirs.work, dirName) } }
        return true
    }

    /** 清掉所有没在用的工作区;返回因为有任务在跑而留下的个数。 */
    suspend fun clearAll(): Int {
        val busy = busyDirs()
        val names = withContext(Dispatchers.IO) { dirs.work.listFiles().orEmpty().map { it.name } }
        dirs.tree.shared {
            withContext(Dispatchers.IO) { names.filter { it !in busy }.forEach { runCatching { dirs.gate.deleteTree(dirs.work, it) } } }
        }
        return names.count { it in busy }
    }

    private fun busyDirs(): Set<String> = active().map { dirs.workspace(it).name }.toSet()
}
