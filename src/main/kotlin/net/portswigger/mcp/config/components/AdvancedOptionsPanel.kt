package net.portswigger.mcp.config.components

import net.portswigger.mcp.config.Design
import net.portswigger.mcp.config.McpConfig
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*
import javax.swing.Box.createVerticalStrut
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ChangeListener

class AdvancedOptionsPanel(
    private val config: McpConfig,
    private val hostField: JTextField,
    private val portField: JTextField,
    private val reinstallNotice: WarningLabel
) : JPanel() {

    private val keepaliveToggle = JCheckBox("启用保活心跳", config.keepaliveEnabled)
    private val keepaliveIntervalSpinner = JSpinner(SpinnerNumberModel(config.keepaliveIntervalSec, 5, 300, 5))
    private val maxResponseSizeSpinner = JSpinner(SpinnerNumberModel(config.maxResponseSizeKb, 10, 5000, 10))
    private val strictLocalhostToggle = JCheckBox("严格 localhost 模式（WSL/远程环境请关闭）", config.strictLocalhostMode)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = Design.Colors.surface
        alignmentX = LEFT_ALIGNMENT

        buildPanel()
        setupFieldTracking()
    }

    private fun buildPanel() {
        // Note: "高级选项" section label removed — provided by Card wrapper in ConfigUi
        val formPanel = createFormPanel(
            "服务器主机:" to hostField, "服务器端口:" to portField
        )
        add(formPanel)

        add(createVerticalStrut(Design.Spacing.MD))
        add(Design.createSectionLabel("连接设置"))
        add(createVerticalStrut(Design.Spacing.MD))

        strictLocalhostToggle.apply {
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            isOpaque = false
        }
        add(strictLocalhostToggle)
        add(createVerticalStrut(Design.Spacing.MD))

        keepaliveToggle.apply {
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            isOpaque = false
        }
        add(keepaliveToggle)

        val connectionForm = createFormPanel(
            "保活间隔（秒）:" to keepaliveIntervalSpinner,
            "最大响应大小（KB）:" to maxResponseSizeSpinner
        )
        add(connectionForm)
    }

    private fun setupFieldTracking() {
        trackChanges(hostField)
        trackChanges(portField)
        strictLocalhostToggle.addChangeListener { reinstallNotice.isVisible = true }
        keepaliveToggle.addChangeListener { reinstallNotice.isVisible = true }
        keepaliveIntervalSpinner.addChangeListener { reinstallNotice.isVisible = true }
        maxResponseSizeSpinner.addChangeListener { reinstallNotice.isVisible = true }
    }

    private fun trackChanges(field: JTextField) {
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = handle()
            override fun removeUpdate(e: DocumentEvent?) = handle()
            override fun changedUpdate(e: DocumentEvent?) = handle()
            fun handle() {
                reinstallNotice.isVisible = true
            }
        })
    }

    private fun createFormPanel(vararg fields: Pair<String, JComponent>): JPanel {
        val formPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        val gbc = GridBagConstraints().apply {
            insets = Insets(Design.Spacing.SM, 0, Design.Spacing.SM, Design.Spacing.MD)
            anchor = GridBagConstraints.WEST
        }

        fields.forEachIndexed { index, (labelText, field) ->
            gbc.gridx = 0
            gbc.gridy = index
            gbc.fill = GridBagConstraints.NONE
            gbc.weightx = 0.0
            formPanel.add(JLabel(labelText).apply {
                font = Design.Typography.bodyLarge
                foreground = Design.Colors.onSurface
            }, gbc)

            gbc.gridx = 1
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.weightx = 1.0
            gbc.insets = Insets(Design.Spacing.SM, 0, Design.Spacing.SM, 0)

            when (field) {
                is JTextField -> {
                    field.preferredSize = Dimension(200, 32)
                    field.font = Design.Typography.bodyLarge
                }
                is JSpinner -> {
                    field.preferredSize = Dimension(120, 32)
                    field.font = Design.Typography.bodyLarge
                }
            }

            formPanel.add(field, gbc)

            gbc.insets = Insets(Design.Spacing.SM, 0, Design.Spacing.SM, Design.Spacing.MD)
        }

        return formPanel
    }

    fun setFieldsEnabled(enabled: Boolean) {
        // Host/port fields always editable - user may need to rebind
        keepaliveToggle.isEnabled = enabled
        keepaliveIntervalSpinner.isEnabled = enabled
        maxResponseSizeSpinner.isEnabled = enabled
        strictLocalhostToggle.isEnabled = enabled
    }

    fun applyConfig() {
        config.keepaliveEnabled = keepaliveToggle.isSelected
        config.keepaliveIntervalSec = keepaliveIntervalSpinner.value as Int
        config.maxResponseSizeKb = maxResponseSizeSpinner.value as Int
        config.strictLocalhostMode = strictLocalhostToggle.isSelected
    }

}
