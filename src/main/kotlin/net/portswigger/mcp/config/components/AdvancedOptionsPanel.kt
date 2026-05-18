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

    private val keepaliveToggle = JCheckBox("Enable keepalive pings", config.keepaliveEnabled)
    private val keepaliveIntervalSpinner = JSpinner(SpinnerNumberModel(config.keepaliveIntervalSec, 5, 300, 5))
    private val maxResponseSizeSpinner = JSpinner(SpinnerNumberModel(config.maxResponseSizeKb, 10, 5000, 10))

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        updateColors()
        alignmentX = LEFT_ALIGNMENT

        buildPanel()
        setupFieldTracking()
    }

    override fun updateUI() {
        super.updateUI()
        updateColors()
    }

    private fun updateColors() {
        background = Design.Colors.surface
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Design.Colors.outlineVariant, 1),
            BorderFactory.createEmptyBorder(Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD, Design.Spacing.MD)
        )
    }

    private fun buildPanel() {
        add(Design.createSectionLabel("Advanced Options"))
        add(createVerticalStrut(Design.Spacing.MD))

        val formPanel = createFormPanel(
            "Server host:" to hostField, "Server port:" to portField
        )
        add(formPanel)

        add(createVerticalStrut(Design.Spacing.MD))
        add(Design.createSectionLabel("Connection Settings"))
        add(createVerticalStrut(Design.Spacing.MD))

        keepaliveToggle.apply {
            font = Design.Typography.bodyLarge
            foreground = Design.Colors.onSurface
            isOpaque = false
        }
        add(keepaliveToggle)

        val connectionForm = createFormPanel(
            "Keepalive interval (s):" to keepaliveIntervalSpinner,
            "Max response size (KB):" to maxResponseSizeSpinner
        )
        add(connectionForm)
    }

    private fun setupFieldTracking() {
        trackChanges(hostField)
        trackChanges(portField)
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
        hostField.isEnabled = enabled
        portField.isEnabled = enabled
        keepaliveToggle.isEnabled = enabled
        keepaliveIntervalSpinner.isEnabled = enabled
        maxResponseSizeSpinner.isEnabled = enabled
    }

    fun applyConfig() {
        config.keepaliveEnabled = keepaliveToggle.isSelected
        config.keepaliveIntervalSec = keepaliveIntervalSpinner.value as Int
        config.maxResponseSizeKb = maxResponseSizeSpinner.value as Int
    }

}
