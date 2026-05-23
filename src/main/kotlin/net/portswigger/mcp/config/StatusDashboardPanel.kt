package net.portswigger.mcp.config

import net.portswigger.mcp.ServerState
import net.portswigger.mcp.db.Database
import net.portswigger.mcp.exporter.Exporter
import net.portswigger.mcp.queue.FileQueue
import net.portswigger.mcp.queue.MessageQueue
import java.awt.*
import javax.swing.*
import javax.swing.Box.createVerticalStrut
import javax.swing.border.EmptyBorder

class StatusDashboardPanel : JPanel() {

    var messageQueue: MessageQueue? = null
    var fileQueue: FileQueue? = null
    var database: Database? = null
    var exporter: Exporter? = null
    var activeConnectionProvider: (() -> Int)? = null
    var onRestartRequested: (() -> Unit)? = null

    private val refreshStatsTimer = Timer(3000) { refreshAll() }

    // --- Service cards ---
    private val serverCard = ServiceIndicatorCard("服务器")
    private val exporterCard = ServiceIndicatorCard("导出器")
    private val queueCard = ServiceIndicatorCard("任务队列")
    private val dbCard = ServiceIndicatorCard("数据库")

    // --- Stats labels ---
    private val queueStatsLabel = createStatsValueLabel()
    private val fileQueueStatsLabel = createStatsValueLabel()
    private val dbStatsLabel = createStatsValueLabel()
    private val exporterStatsLabel = createStatsValueLabel()
    private val clientCountLabel = createStatsValueLabel()
    private val clientCountBadge = Design.createBadge("0", Design.Colors.primary)
    private val dbHttpBadge = Design.createBadge("0", Design.Colors.tertiary)
    private val dbScanBadge = Design.createBadge("0", Design.Colors.warning)
    private val queuePendingBadge = Design.createBadge("0", Design.Colors.primary)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Design.Colors.surface
        alignmentX = Component.LEFT_ALIGNMENT
        border = BorderFactory.createEmptyBorder(Design.Spacing.LG, Design.Spacing.LG, Design.Spacing.LG, Design.Spacing.LG)

        buildPanel()
    }

    private fun buildPanel() {
        // Title
        add(JLabel("Burp MCP Server 状态看板").apply {
            font = Design.Typography.headlineMedium
            foreground = Design.Colors.onSurface
            alignmentX = Component.LEFT_ALIGNMENT
        })
        add(createVerticalStrut(Design.Spacing.MD))

        // --- Service Status Cards (2x2 grid) ---
        add(createSectionLabel("服务状态"))
        add(createVerticalStrut(Design.Spacing.SM))

        val cardGrid = JPanel(GridLayout(2, 2, Design.Spacing.SM, Design.Spacing.SM)).apply {
            background = Design.Colors.surface
            alignmentX = Component.LEFT_ALIGNMENT
            // Ensure cards don't stretch too wide
            maximumSize = Dimension(Integer.MAX_VALUE, preferredSize.height)
        }

        cardGrid.add(wrapInMiniCard(serverCard))
        cardGrid.add(wrapInMiniCard(exporterCard))
        cardGrid.add(wrapInMiniCard(queueCard))
        cardGrid.add(wrapInMiniCard(dbCard))
        add(cardGrid)

        add(createVerticalStrut(Design.Spacing.LG))

        // --- Stats Section ---
        add(createSectionLabel("运行统计"))
        add(createVerticalStrut(Design.Spacing.SM))

        add(createCompactStatsRow("消息队列", queuePendingBadge, queueStatsLabel))
        add(createVerticalStrut(Design.Spacing.SM))
        add(createCompactStatsRow("文件队列", null, fileQueueStatsLabel))
        add(createVerticalStrut(Design.Spacing.SM))

        // DB stats with two badges
        val dbStatsRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("数据库").apply {
                font = Design.Typography.bodyMedium
                foreground = Design.Colors.onSurfaceVariant
                minimumSize = Dimension(60, 0)
                preferredSize = Dimension(60, 0)
            })
            add(Box.createHorizontalStrut(Design.Spacing.SM))
            add(dbHttpBadge)
            add(Box.createHorizontalStrut(4))
            add(JLabel("HTTP").apply {
                font = Design.Typography.labelSmall
                foreground = Design.Colors.onSurfaceVariant
            })
            add(Box.createHorizontalStrut(Design.Spacing.SM))
            add(dbScanBadge)
            add(Box.createHorizontalStrut(4))
            add(JLabel("扫描").apply {
                font = Design.Typography.labelSmall
                foreground = Design.Colors.onSurfaceVariant
            })
            add(Box.createHorizontalGlue())
        }
        add(dbStatsRow)
        add(createVerticalStrut(Design.Spacing.SM))

        add(createCompactStatsRow("导出器", null, exporterStatsLabel))
        add(createVerticalStrut(Design.Spacing.SM))

        add(createCompactStatsRow("客户端", clientCountBadge, clientCountLabel))

        add(createVerticalStrut(Design.Spacing.LG))

        // --- Management ---
        add(createSectionLabel("管理"))
        add(createVerticalStrut(Design.Spacing.SM))

        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            background = Design.Colors.surface
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val clearDbButton = Design.createOutlinedButton("清除缓存").apply {
            addActionListener {
                val result = Dialogs.showConfirmDialog(this@StatusDashboardPanel,
                    "确定要清除所有缓存数据吗？\n此操作不可撤销。", JOptionPane.YES_NO_OPTION)
                if (result == JOptionPane.YES_OPTION) {
                    database?.clearAll()
                    refreshAll()
                }
            }
        }
        buttonRow.add(clearDbButton)
        buttonRow.add(Box.createHorizontalStrut(Design.Spacing.SM))

        val restartButton = Design.createFilledButton("重启服务器").apply {
            addActionListener {
                val result = Dialogs.showConfirmDialog(this@StatusDashboardPanel,
                    "确定要重启 MCP 服务器吗？\n客户端连接将暂时中断。", JOptionPane.YES_NO_OPTION)
                if (result == JOptionPane.YES_OPTION) {
                    onRestartRequested?.invoke()
                }
            }
        }
        buttonRow.add(restartButton)

        add(buttonRow)
    }

    // --- Public API ---

    fun startRefreshing() {
        if (!refreshStatsTimer.isRunning) {
            refreshStatsTimer.start()
        }
    }

    fun stopRefreshing() {
        refreshStatsTimer.stop()
    }

    fun updateServerState(state: ServerState) {
        when (state) {
            ServerState.Starting -> serverCard.setStatus(StatusDot.ColorYELLOW, "启动中")
            ServerState.Running -> serverCard.setStatus(StatusDot.ColorGREEN, "运行中")
            ServerState.Stopping -> serverCard.setStatus(StatusDot.ColorYELLOW, "停止中")
            ServerState.Stopped -> serverCard.setStatus(StatusDot.ColorGRAY, "已停止")
            is ServerState.Failed -> serverCard.setStatus(StatusDot.ColorRED, "启动失败")
        }
    }

    fun refreshAll() {
        refreshIndicators()
        refreshStats()
    }

    // --- Internal ---

    private fun refreshIndicators() {
        // Exporter
        val exporterStats = exporter?.stats
        if (exporterStats != null && exporterStats.isRunning) {
            exporterCard.setStatus(StatusDot.ColorGREEN, "运行中")
            exporterCard.setDetail("已导出 ${exporterStats.totalExported} 条")
        } else {
            exporterCard.setStatus(StatusDot.ColorGRAY, "已停止")
            exporterCard.setDetail("")
        }

        // MessageQueue
        val qStats = messageQueue?.stats
        if (qStats != null) {
            if (qStats.processing > 0) {
                queueCard.setStatus(StatusDot.ColorGREEN, "处理中")
                queueCard.setDetail("${qStats.processing} 个任务进行中")
            } else if (qStats.submitted > 0) {
                queueCard.setStatus(StatusDot.ColorBLUE, "空闲")
                queueCard.setDetail("历史 ${qStats.submitted} 条")
            } else {
                queueCard.setStatus(StatusDot.ColorGRAY, "空闲")
                queueCard.setDetail("")
            }
        } else {
            queueCard.setStatus(StatusDot.ColorGRAY, "未连接")
            queueCard.setDetail("")
        }

        // Database
        val dbStats = database?.stats()
        if (dbStats != null && (dbStats.proxyHttpCount > 0 || dbStats.scannerIssueCount > 0)) {
            dbCard.setStatus(StatusDot.ColorGREEN, "已缓存")
            val total = dbStats.proxyHttpCount + dbStats.scannerIssueCount
            dbCard.setDetail("$total 条数据")
        } else if (dbStats != null) {
            dbCard.setStatus(StatusDot.ColorGRAY, "空缓存")
            dbCard.setDetail("")
        } else {
            dbCard.setStatus(StatusDot.ColorGRAY, "未连接")
            dbCard.setDetail("")
        }
    }

    private fun refreshStats() {
        // MessageQueue stats
        val qStats = messageQueue?.stats
        if (qStats != null) {
            queueStatsLabel.text = "提交: ${qStats.submitted}  完成: ${qStats.completed}  失败: ${qStats.failed}  处理中: ${qStats.processing}"
            queuePendingBadge.text = qStats.processing.toString()
            queuePendingBadge.isVisible = qStats.processing > 0
        } else {
            queueStatsLabel.text = "--"
            queuePendingBadge.isVisible = false
        }

        // FileQueue stats
        val fStats = fileQueue?.stats()
        fileQueueStatsLabel.text = fStats?.let {
            "文件数: ${it.totalFiles}  总大小: ${formatBytes(it.totalSizeBytes)}  访问: ${it.totalAccesses}"
        } ?: "--"

        // DB stats
        val dStats = database?.stats()
        if (dStats != null) {
            dbStatsLabel.text = "共 ${dStats.proxyHttpCount + dStats.scannerIssueCount} 条"
            dbHttpBadge.text = dStats.proxyHttpCount.toString()
            dbScanBadge.text = dStats.scannerIssueCount.toString()
        } else {
            dbStatsLabel.text = "--"
            dbHttpBadge.text = "0"
            dbScanBadge.text = "0"
        }

        // Connected clients
        val connCount = activeConnectionProvider?.invoke() ?: 0
        clientCountBadge.text = connCount.toString()
        clientCountLabel.text = when {
            connCount <= 0 -> "无连接"
            connCount == 1 -> "1 个活跃连接"
            else -> "$connCount 个活跃连接"
        }
        clientCountBadge.isVisible = connCount > 0

        // Exporter stats
        val eStats = exporter?.stats
        exporterStatsLabel.text = eStats?.let {
            val lastExport = if (it.lastExportTime > 0) "是" else "从未"
            "已导出: ${it.totalExported}  最后导出: $lastExport  运行中: ${if (it.isRunning) "是" else "否"}"
        } ?: "--"
    }

    // --- Helpers ---

    private fun wrapInMiniCard(content: JComponent): JPanel {
        return object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Design.Colors.outlineVariant, 1),
                    BorderFactory.createEmptyBorder(Design.Spacing.SM, Design.Spacing.MD, Design.Spacing.SM, Design.Spacing.MD)
                )
                add(content, BorderLayout.CENTER)
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = Design.Colors.surface
                g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
                g2.dispose()
            }
        }
    }

    private fun createSectionLabel(text: String): JLabel {
        return JLabel(text).apply {
            font = Design.Typography.titleMedium
            foreground = Design.Colors.onSurface
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private fun createCompactStatsRow(label: String, badge: JLabel? = null, valueLabel: JLabel): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel(label).apply {
                font = Design.Typography.bodyMedium
                foreground = Design.Colors.onSurfaceVariant
                minimumSize = Dimension(60, 0)
                preferredSize = Dimension(60, 0)
            })
            add(Box.createHorizontalStrut(Design.Spacing.SM))
            if (badge != null) {
                add(badge)
                add(Box.createHorizontalStrut(Design.Spacing.SM))
                badge.isVisible = false // hidden by default, shown when data present
            }
            add(valueLabel)
            add(Box.createHorizontalGlue())
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
        }
    }

    companion object {
        private fun createStatsValueLabel(): JLabel {
            return JLabel().apply {
                font = Design.Typography.bodyMedium
                foreground = Design.Colors.onSurface
            }
        }
    }
}

/**
 * A compact indicator card showing a colored dot, name, status text, and optional detail line.
 */
class ServiceIndicatorCard(private val name: String) : JPanel() {
    private val dot = StatusDot()
    private val statusLabel = JLabel("--")
    private val detailLabel = JLabel("")

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false

        val topRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT

            dot.alignmentY = Component.CENTER_ALIGNMENT
            add(dot)
            add(Box.createHorizontalStrut(6))

            val nameComp = JLabel(name).apply {
                font = Design.Typography.bodyLarge
                foreground = Design.Colors.onSurface
                alignmentY = Component.CENTER_ALIGNMENT
            }
            add(nameComp)
            add(Box.createHorizontalGlue())

            statusLabel.font = Design.Typography.labelSmall
            statusLabel.foreground = Design.Colors.onSurfaceVariant
            statusLabel.alignmentY = Component.CENTER_ALIGNMENT
            add(statusLabel)
        }
        add(topRow)

        detailLabel.font = Design.Typography.labelSmall
        detailLabel.foreground = Design.Colors.onSurfaceVariant
        detailLabel.alignmentX = Component.LEFT_ALIGNMENT
        detailLabel.border = BorderFactory.createEmptyBorder(2, 18, 0, 0)
        add(detailLabel)
    }

    fun setStatus(color: Color, text: String) {
        dot.color = color
        dot.isPulsing = (color == StatusDot.ColorGREEN)
        statusLabel.text = text
    }

    fun setDetail(text: String) {
        detailLabel.text = text
        detailLabel.isVisible = text.isNotEmpty()
    }
}

class StatusDot : JComponent() {
    var color: Color = ColorGRAY
        set(value) {
            field = value
            repaint()
        }

    var isPulsing: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) startPulse() else stopPulse()
            }
        }

    private var pulseTimer: Timer? = null
    private var pulsePhase = 0f

    override fun getPreferredSize(): Dimension = Dimension(14, 14)
    override fun getMinimumSize(): Dimension = Dimension(14, 14)

    private fun startPulse() {
        pulseTimer?.stop()
        pulseTimer = Timer(30) { _ ->
            pulsePhase = (pulsePhase + 0.08f) % (Math.PI * 2).toFloat()
            repaint()
        }
        pulseTimer?.start()
    }

    private fun stopPulse() {
        pulseTimer?.stop()
        pulseTimer = null
        pulsePhase = 0f
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val size = if (isPulsing) {
            val pulse = (Math.sin(pulsePhase.toDouble()) * 0.15 + 0.85).toFloat()
            14f * pulse
        } else {
            14f
        }
        val offset = (14 - size) / 2f
        val cy = (height - 14) / 2f

        if (isPulsing) {
            val alpha = ((Math.sin(pulsePhase.toDouble()) * 0.3 + 0.7).toFloat() * 255).toInt()
            g2.color = Color(color.red, color.green, color.blue, alpha)
        } else {
            g2.color = color
        }
        g2.fillOval((0 + offset).toInt(), (cy + offset).toInt(), size.toInt(), size.toInt())
        g2.dispose()
    }

    companion object {
        val ColorGREEN = Color(0x4CAF50)
        val ColorYELLOW = Color(0xFFC107)
        val ColorRED = Color(0xF44336)
        val ColorGRAY = Color(0x9E9E9E)
        val ColorBLUE = Color(0x2196F3)
    }
}
