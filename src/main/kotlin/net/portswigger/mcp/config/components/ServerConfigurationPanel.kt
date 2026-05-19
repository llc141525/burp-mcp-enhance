package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.config.McpConfig
import net.portswigger.mcp.config.ToggleSwitch
import java.awt.FlowLayout
import java.awt.event.ItemEvent
import javax.swing.*
import javax.swing.Box.createHorizontalStrut
import javax.swing.Box.createVerticalStrut

class ServerConfigurationPanel(
    private val config: McpConfig,
    private val enabledToggle: ToggleSwitch,
    private val validationErrorLabel: WarningLabel
) : JPanel() {

    private lateinit var alwaysAllowHttpHistoryCheckBox: JCheckBox
    private lateinit var alwaysAllowWebSocketHistoryCheckBox: JCheckBox

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Design.Colors.surface
        alignmentX = LEFT_ALIGNMENT

        buildPanel()
    }

    private fun buildPanel() {
        val enabledPanel = createEnabledPanel()
        add(enabledPanel)
        add(createVerticalStrut(Design.Spacing.MD))

        val configEditingToolingCheckBox = createCheckBoxWithSubtitle(
            "启用可修改配置的工具",
            "警告：可执行代码",
            config.configEditingTooling
        ) { config.configEditingTooling = it }
        add(configEditingToolingCheckBox)
        add(createVerticalStrut(Design.Spacing.MD))

        val httpRequestApprovalCheckBox = createStandardCheckBox(
            "HTTP 请求需要审批", config.requireHttpRequestApproval
        ) { config.requireHttpRequestApproval = it }
        add(httpRequestApprovalCheckBox)
        add(createVerticalStrut(Design.Spacing.MD))

        val historyAccessApprovalCheckBox = createHistoryAccessApprovalCheckBox()
        add(historyAccessApprovalCheckBox)
        add(createVerticalStrut(Design.Spacing.SM))

        alwaysAllowHttpHistoryCheckBox = createIndentedCheckBox(
            "始终允许 HTTP 历史记录访问", config.alwaysAllowHttpHistory, config.requireHistoryAccessApproval
        ) { config.alwaysAllowHttpHistory = it }
        add(alwaysAllowHttpHistoryCheckBox)
        add(createVerticalStrut(Design.Spacing.SM))

        alwaysAllowWebSocketHistoryCheckBox = createIndentedCheckBox(
            "始终允许 WebSocket 历史记录访问",
            config.alwaysAllowWebSocketHistory,
            config.requireHistoryAccessApproval
        ) { config.alwaysAllowWebSocketHistory = it }
        add(alwaysAllowWebSocketHistoryCheckBox)

        add(validationErrorLabel)
    }

    private fun createEnabledPanel(): JPanel {
        val enabledPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        enabledPanel.add(JLabel("已启用").apply {
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
        })
        enabledPanel.add(createHorizontalStrut(Design.Spacing.MD))
        enabledPanel.add(enabledToggle)
        return enabledPanel
    }

    private fun createHistoryAccessApprovalCheckBox(): JCheckBox {
        return createStandardCheckBox(
            "历史记录访问需要审批", config.requireHistoryAccessApproval
        ) { enabled ->
            config.requireHistoryAccessApproval = enabled
            if (!enabled) {
                config.alwaysAllowHttpHistory = false
                config.alwaysAllowWebSocketHistory = false
                alwaysAllowHttpHistoryCheckBox.isSelected = false
                alwaysAllowWebSocketHistoryCheckBox.isSelected = false
            }
            alwaysAllowHttpHistoryCheckBox.isEnabled = enabled
            alwaysAllowWebSocketHistoryCheckBox.isEnabled = enabled
        }
    }

    fun updateHistoryAccessCheckboxes() {
        SwingUtilities.invokeLater {
            alwaysAllowHttpHistoryCheckBox.isSelected = config.alwaysAllowHttpHistory
            alwaysAllowWebSocketHistoryCheckBox.isSelected = config.alwaysAllowWebSocketHistory
        }
    }

    private fun createStandardCheckBox(
        text: String, initialValue: Boolean, onChange: (Boolean) -> Unit
    ): JCheckBox {
        return JCheckBox(text).apply {
            alignmentX = LEFT_ALIGNMENT
            isSelected = initialValue
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            addItemListener { event ->
                onChange(event.stateChange == ItemEvent.SELECTED)
            }
        }
    }

    private fun createIndentedCheckBox(
        text: String, initialValue: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit
    ): JCheckBox {
        return JCheckBox(text).apply {
            alignmentX = LEFT_ALIGNMENT
            isSelected = initialValue
            isEnabled = enabled
            font = Design.Typography.bodyMedium
            foreground = Design.Colors.onSurfaceVariant
            border = BorderFactory.createEmptyBorder(0, Design.Spacing.LG, 0, 0)
            addItemListener { event ->
                onChange(event.stateChange == ItemEvent.SELECTED)
            }
        }
    }

    private fun createCheckBoxWithSubtitle(
        mainText: String, subtitleText: String, initialValue: Boolean, onChange: (Boolean) -> Unit
    ): JPanel {
        val checkBox = JCheckBox(mainText).apply {
            alignmentX = LEFT_ALIGNMENT
            isSelected = initialValue
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            addItemListener { event ->
                onChange(event.stateChange == ItemEvent.SELECTED)
            }
        }

        val subtitleLabel = JLabel(subtitleText).apply {
            font = Design.Typography.labelMedium
            foreground = Design.Colors.onSurfaceVariant
        }

        val subtitlePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(createHorizontalStrut(20))
            add(subtitleLabel)
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            isOpaque = false
            add(checkBox)
            add(subtitlePanel)
        }
    }

}