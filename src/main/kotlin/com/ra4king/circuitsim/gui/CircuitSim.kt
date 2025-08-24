package com.ra4king.circuitsim.gui

import com.google.gson.JsonSyntaxException
import com.ra4king.circuitsim.gui.ComponentManager.ComponentCreator
import com.ra4king.circuitsim.gui.ComponentManager.ComponentLauncherInfo
import com.ra4king.circuitsim.gui.EditHistory.*
import com.ra4king.circuitsim.gui.GuiUtils.getFont
import com.ra4king.circuitsim.gui.LinkWires.Wire
import com.ra4king.circuitsim.gui.Properties.Property
import com.ra4king.circuitsim.gui.file.FileFormat
import com.ra4king.circuitsim.gui.file.FileFormat.CircuitFile
import com.ra4king.circuitsim.gui.file.FileFormat.CircuitInfo
import com.ra4king.circuitsim.gui.file.FileFormat.ComponentInfo
import com.ra4king.circuitsim.gui.file.FileFormat.WireInfo
import com.ra4king.circuitsim.gui.file.FileFormat.stringify
import com.ra4king.circuitsim.gui.peers.SubcircuitPeer
import com.ra4king.circuitsim.gui.properties.PropertyCircuitValidator
import com.ra4king.circuitsim.simulator.*
import com.ra4king.circuitsim.simulator.components.Subcircuit
import com.ra4king.circuitsim.simulator.components.wiring.Clock
import com.ra4king.circuitsim.simulator.components.wiring.Clock.Companion.getLastTickCount
import com.ra4king.circuitsim.simulator.components.wiring.Clock.Companion.isRunning
import com.ra4king.circuitsim.simulator.components.wiring.Pin
import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.value.ChangeListener
import javafx.embed.swing.SwingFXUtils
import javafx.event.Event
import javafx.event.EventHandler
import javafx.event.EventType
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Point2D
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.control.Alert.AlertType
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.*
import javafx.scene.input.KeyCode.*
import javafx.scene.input.KeyCombination.SHIFT_DOWN
import javafx.scene.input.KeyCombination.SHORTCUT_DOWN
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.scene.text.TextFlow
import javafx.stage.DirectoryChooser
import javafx.stage.FileChooser
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.util.Duration
import javafx.util.StringConverter
import menuBar
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.image.RenderedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.lang.Boolean.FALSE
import java.math.RoundingMode
import java.nio.file.Files
import java.text.DecimalFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import java.util.stream.Collectors
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.Delegates

class CircuitSim @JvmOverloads constructor(val openWindow: Boolean, val init: Boolean = true, var debugMode: Boolean = false) : Application() {

    lateinit var scene: Scene
    lateinit var clockEnabled: CheckMenuItem
    lateinit var help: MenuItem
    lateinit var frequenciesMenu: Menu
    lateinit var undo: MenuItem
    lateinit var redo: MenuItem

    val debugUtil = DebugUtil(this)
    private var selectedComponent: ComponentLauncherInfo? = null
    private var saveFile: File? = null
    private var lastSaveFile: File? = null
    private var loadingFile = false
    private var savedEditStackSize = 0
    private var circuitButtonsTab: TitledPane? = null
    private var currentTimer: AnimationTimer? = null

    var initContext: InitContext by Delegates.notNull()
    var startContext: StartContext by Delegates.notNull()

    val simulator get() = initContext.simulator
    val componentManager get() = initContext.componentManager
    val editHistory get() = initContext.editHistory
    val circuitManagers get() = initContext.circuitManagers

    val stage get() = startContext.stage
    val simulationEnabled get() = startContext.simulationEnabled
    val isSimulationEnabled get() = simulationEnabled.isSelected
    val clickMode get() = startContext.clickMode
    var isClickMode
        get() = clickMode.isSelected
        set(value) {
            if (!clickedDirectly) clickMode.isSelected = value
        }
    val scaleFactorSlider get() = startContext.scaleFactorSlider
    var scaleFactor
        get() = scaleFactorSlider.value
        set(value) {
            scaleFactorSlider.value = clampScaleFactor(value)
        }
    val scaleFactorInverted get() = 1.0 / scaleFactor
    val bitSizeSelect get() = startContext.bitSizeSelect
    val buttonTabPane get() = startContext.buttonTabPane
    val buttonsToggleGroup get() = startContext.buttonsToggleGroup
    val circuitCanvas get() = startContext.circuitCanvas
    val canvasScrollPane get() = startContext.canvasScrollPane
    val canvasTabPane get() = startContext.canvasTabPane
    val fpsLabel get() = startContext.fpsLabel
    val clockLabel get() = startContext.clockLabel
    val messageLabel get() = startContext.messageLabel
    val propertiesTable get() = startContext.propertiesTable
    val componentLabel get() = startContext.componentLabel

    val circuitBoards get() = LinkedHashMap(circuitManagers.entries.associate { (k, v) -> k to v.second.circuitBoard })
    val currentCircuit: CircuitManager?
        get() {
            val tab = canvasTabPane.selectionModel?.selectedItem ?: return null
            if (!canvasTabPane.tabs.contains(tab)) return null
            return circuitManagers[tab.text]?.second
        }
    val currentError: String
        get() {
            val manager = currentCircuit
            if (lastException != null && System.currentTimeMillis() - lastExceptionTime!! > SHOW_ERROR_DURATION)
                lastException = null
            val e = lastException ?: manager?.currentError
            return if (e is ShortCircuitException) "Short circuit detected" else e?.message ?: ""
        }

    private val currentClockSpeed
        get() = frequenciesMenu.items
            .map { it as RadioMenuItem }
            .firstOrNull { it.isSelected }?.text
            ?.substringBefore(" ")
            ?.toIntOrNull()
            ?: throw IllegalStateException("No frequency selected")

    private var lastExceptionTime: Long? = null
    private var lastException: Exception? = null
        set(value) {
            field = value
            lastExceptionTime = if (value == null) null else System.currentTimeMillis()
        }
    var needsRepaint = false
    var clickedDirectly = false
    val defaultProperties: Properties
        get() {
            val properties = Properties()
            properties.setValue(Properties.BITSIZE, bitSizeSelect.selectionModel.selectedItem)
            return properties
        }
    private val revisionSignatures = LinkedList<String>()
    private val copiedBlocks = LinkedList<String>()
    private var exceptionThrown: Exception? = null
    private val showGridProp = SimpleBooleanProperty(true)

    constructor() : this(true, false)

    init {
        if (init) {
            runFxSync {
                init()
                start(Stage())
            }
        }
    }

    override fun init() {
        initContext = InitContext(
            Simulator(),
            HashMap(),
            EditHistory(this),
            ComponentManager()
        )
        Clock.addChangeListener(simulator) { runSim() }
        editHistory.addListener {
            updateTitle()
            needsRepaint = true
        }
    }

    fun setNeedsRepaint() {
        needsRepaint = true
    }

    private fun runSim() {
        try {
            if (isSimulationEnabled && simulator.hasLinksToUpdate()) {
                needsRepaint = true
                simulator.stepAll()
            }
        } catch (e: SimulationException) {
            lastException = e
        } catch (e: Exception) {
            lastException = e
            debugUtil.logException(e)
        }
    }

    fun getCircuitName(manager: CircuitManager) =
        circuitManagers.entries.find { (_, pair) -> pair.second == manager }?.key

    fun getCircuitManager(name: String) = circuitManagers[name]?.second
    private fun getCircuitManager(circuit: Circuit) =
        circuitManagers.values.find { it.second.circuit == circuit }?.second

    private fun getTabForCircuit(name: String): Tab? = canvasTabPane.tabs.find { it.text == name }
    private fun getTabForCircuit(circuit: Circuit): Tab? =
        circuitManagers.entries.find { it.value.second.circuit == circuit }?.key?.let(::getTabForCircuit)

    /**
     * Selects the tab of the specified circuit and changes its current state to the specified state.
     *
     * @param circuit The circuit whose tab will be selected
     * @param state   The state to set as the current state. Might be null (no change to the current state).
     */
    fun switchToCircuit(circuit: Circuit, state: CircuitState?) = runFxSync {
        state?.let { getCircuitManager(circuit)?.switchToCircuitState(it) }
        getTabForCircuit(circuit)?.also {
            canvasTabPane.selectionModel.select(it)
            needsRepaint = true
        } ?: return@runFxSync
    }

    fun readdCircuit(manager: CircuitManager, tab: Tab, index: Int) {
        canvasTabPane.tabs.add(min(index, canvasTabPane.tabs.size), tab)
        circuitManagers[tab.text] = Pair(createSubcircuitLauncherInfo(tab.text), manager)
        manager.switchToCircuitState()
        canvasTabPane.selectionModel.select(tab)
        refreshCircuitsTab()
    }

    fun confirmAndDeleteCircuit(circuitManager: CircuitManager, removeTab: Boolean): Boolean {
        val alert = Alert(AlertType.CONFIRMATION)
        alert.initOwner(stage)
        alert.initModality(Modality.WINDOW_MODAL)
        alert.title = "Delete \"${circuitManager.name}\"?"
        alert.headerText = "Delete \"${circuitManager.name}\"?"
        alert.contentText = "Are you sure you want to delete this circuit?"

        val result = alert.showAndWait()
        if (result.isEmpty || result.get() != ButtonType.OK) {
            return false
        } else {
            deleteCircuit(circuitManager, removeTab, true)
            return true
        }
    }

    @JvmOverloads
    fun deleteCircuit(name: String, addNewOnEmpty: Boolean = true): Boolean {
        deleteCircuit(getCircuitManager(name) ?: return false, true, addNewOnEmpty)
        return true
    }

    fun deleteCircuit(manager: CircuitManager, removeTab: Boolean = true, addNewOnEmpty: Boolean = true) = runFxSync {
        clearSelection()
        val tab = getTabForCircuit(manager.circuit) ?: throw IllegalStateException("Tab shouldn't be null.")

        val idx = canvasTabPane.tabs.indexOf(tab)
        if (idx == -1) throw IllegalStateException("Tab not found in the pane.")

        val isEmpty = if (removeTab) {
            canvasTabPane.tabs.remove(tab)
            canvasTabPane.tabs.isEmpty()
        } else canvasTabPane.tabs.size == 1

        editHistory.beginGroup()

        val removed = circuitManagers.remove(tab.text)!!
        circuitModified(removed.second.circuit, null, false)
        removed.second.destroy()

        editHistory.addAction(DeleteCircuit(manager, tab, idx))

        if (addNewOnEmpty && isEmpty) {
            createCircuit("New circuit")
            canvasTabPane.selectionModel.select(0)
        }

        editHistory.endGroup()
        refreshCircuitsTab()
    }

    fun clearProperties() = setProperties("", null)

    fun setProperties(componentPeer: ComponentPeer<*>) = setProperties(
        if (componentPeer is SubcircuitPeer)
            componentPeer.properties.getProperty<Any>(SubcircuitPeer.SUBCIRCUIT).stringValue
        else componentManager.get(componentPeer.javaClass, componentPeer.properties).name.second,
        componentPeer.properties
    )

    fun setProperties(componentName: String, properties: Properties?) {
        propertiesTable.children.clear()
        componentLabel.text = componentName

        properties?.forEach(object : Consumer<Property<*>> {
            override fun accept(property: Property<*>) {
                @Suppress("UNCHECKED_CAST")
                acceptProperty(property as Property<Any>)
            }

            private fun <T> acceptProperty(property: Property<T>) {
                if (property.hidden) {
                    return
                }

                val size = propertiesTable.children.size

                val name = Label(property.display)

                if (!property.helpText.isEmpty()) {
                    val tooltip = Tooltip(property.helpText)
                    tooltip.showDelay = Duration.millis(200.0)
                    tooltip.font = Font.font(11.0)
                    name.tooltip = tooltip
                }

                GridPane.setHgrow(name, Priority.ALWAYS)
                name.maxWidth = Double.Companion.MAX_VALUE
                name.styleClass.add("props-menu-label")

                val node = property.validator.createGui(stage, property.value) { newValue: T? ->
                    Platform.runLater {
                        val newProperties = Properties(properties)
                        newProperties.setValue(property, newValue)
                        updateProperties(newProperties)
                    }
                } ?: return

                val valuePane = StackPane(node)
                StackPane.setAlignment(node, Pos.CENTER_LEFT)
                valuePane.styleClass.add("props-menu-value")
                GridPane.setHgrow(valuePane, Priority.ALWAYS)
                GridPane.setVgrow(valuePane, Priority.ALWAYS)
                propertiesTable.addRow(size, name, valuePane)
            }
        })
    }

    fun clearSelection() {
        buttonsToggleGroup.selectedToggle?.isSelected = false
        modifiedSelection(null)
    }

    private fun updateProperties(properties: Properties) {
        selectedComponent?.let {
            modifiedSelection(it.creator, defaultProperties.union(it.properties).union(properties))
        } ?: modifiedSelection(null, properties)
    }

    private fun modifiedSelection(component: ComponentLauncherInfo?) {
        selectedComponent = component
        component?.let {
            modifiedSelection(it.creator, defaultProperties.union(it.properties))
        } ?: modifiedSelection(null, null)
    }

    private fun modifiedSelection(creator: ComponentCreator<*>?, properties: Properties?) =
        currentCircuit?.modifiedSelection(creator, properties)

    private fun setupImageView(image: Image): ImageView {
        val imageView = ImageView(image)
        imageView.isSmooth = true
        imageView.maxHeight(20.0)
        return imageView
    }

    private fun setupButton(group: ToggleGroup, componentInfo: ComponentLauncherInfo): ToggleButton {
        val button = ToggleButton(componentInfo.name.second, setupImageView(componentInfo.image!!))
        button.alignment = Pos.CENTER_LEFT
        button.toggleGroup = group
        button.minHeight = 30.0
        button.maxWidth = Double.Companion.MAX_VALUE
        button.onAction = EventHandler { modifiedSelection(if (button.isSelected) componentInfo else null) }
        button.styleClass.add("new-component-btn")
        GridPane.setHgrow(button, Priority.ALWAYS)
        return button
    }

    fun refreshCircuitsTab() {
        if (loadingFile) return
        Platform.runLater {
            val flowPane = FlowPane(Orientation.HORIZONTAL).apply {
                hgap = 10.0
                vgap = 10.0
                prefHeight = Region.USE_COMPUTED_SIZE
                minHeight = Region.USE_COMPUTED_SIZE
                maxHeight = Region.USE_COMPUTED_SIZE
            }
            val pane = VBox(flowPane).apply {
                prefHeightProperty().bind(flowPane.heightProperty())
            }
            circuitButtonsTab?.let {
                val buttons = (it.content as VBox).children[0] as FlowPane
                buttons.children.forEach { node -> (node as ToggleButton).toggleGroup = null }
                buttons.children.clear()
                it.content = pane
            } ?: run {
                val circuitButtonsTab = TitledPane("Circuits", pane)
                circuitButtonsTab.styleClass.add("new-component-section")
                circuitButtonsTab.styleClass.add("titled-pane-last")
                this.circuitButtonsTab = circuitButtonsTab
                buttonTabPane.panes.add(circuitButtonsTab)
            }
            val seen = HashSet<String>()
            canvasTabPane.tabs.forEach { tab ->
                val name = tab.text
                val pair = circuitManagers[name] ?: return@forEach
                if (seen.contains(name)) return@forEach
                seen.add(name)
                val component = pair.first.creator.createComponent(Properties(), 0, 0)

                val icon = Canvas((component.screenWidth + 10).toDouble(), (component.screenHeight + 10).toDouble())
                val graphics = icon.graphicsContext2D
                graphics.translate(5.0, 5.0)
                component.paint(icon.graphicsContext2D, null)
                component.connections.forEach { it.paint(icon.graphicsContext2D, null) }

                val toggleButton = ToggleButton(pair.first.name.second, icon)
                toggleButton.styleClass.add("new-component-btn")
                toggleButton.alignment = Pos.CENTER_LEFT
                toggleButton.toggleGroup = buttonsToggleGroup
                toggleButton.minHeight = 30.0
                toggleButton.maxWidth = Double.Companion.MAX_VALUE
                toggleButton.onAction = EventHandler {
                    modifiedSelection(if (toggleButton.isSelected) pair.first else null)
                }
                GridPane.setHgrow(toggleButton, Priority.ALWAYS)

                val buttons = pane.children[0] as FlowPane
                buttons.children.add(toggleButton)

            }
        }
    }

    private fun updateTitle() {
        var name = ""
        saveFile?.let { name = " - ${it.name}" }
        if (editHistory.editStackSize() != savedEditStackSize)
            name += " *"
        stage.title = VERSION_TAG_LINE + name
    }

    private fun getSubcircuitPeerCreator(name: String) = ComponentCreator { props, x, y ->
        val properties = Properties(props)
        properties.parseAndSetValue(SubcircuitPeer.SUBCIRCUIT, PropertyCircuitValidator(this), name)
        try {
            SubcircuitPeer(properties, x, y)
        } catch (exc: SimulationException) {
            throw SimulationException("Error creating subcircuit for circuit '$name'", exc)
        } catch (exc: Exception) {
            throw RuntimeException("Error creating subcircuit for circuit '$name':", exc)
        }
    }

    private fun createSubcircuitLauncherInfo(name: String) = ComponentLauncherInfo(
        SubcircuitPeer::class.java, Pair("Circuits", name),
        null, Properties(), true, getSubcircuitPeerCreator(name)
    )

    fun renameCircuit(tab: Tab, newName: String) = runFxSync {
        require(!circuitManagers.containsKey(newName)) { "Name already exists" }
        val oldName = tab.text

        val removed = circuitManagers.remove(oldName)!!
        val newPair = Pair(createSubcircuitLauncherInfo(newName), removed.second)
        circuitManagers[newName] = newPair

        circuitManagers.values.forEach { componentPair ->
            for (componentPeer in componentPair.second.circuitBoard.components) {
                val component = componentPeer.component
                if (component is Subcircuit && component.subcircuit == removed.second.circuit) {
                    componentPeer.properties.parseAndSetValue(SubcircuitPeer.SUBCIRCUIT, newName)
                }
            }
        }

        tab.text = newName
        newPair.second.name = newName

        editHistory.addAction(RenameCircuit(getCircuitManager(newName)!!, tab, oldName, newName))
        refreshCircuitsTab()
    }

    fun renameCircuit(oldName: String, newName: String) = getTabForCircuit(oldName)?.let { renameCircuit(it, newName) }

    fun updateCanvasSize() = runFxSync {
        circuitCanvas.width = canvasScrollPane.width
        circuitCanvas.height = canvasScrollPane.height
        needsRepaint = true
    }

    /**
     * if component == null, the circuit was deleted
     */
    fun circuitModified(circuit: Circuit, component: Component?, added: Boolean) = simulator.runSync {
        if (component == null || component is Pin) {
            refreshCircuitsTab()
            circuitManagers.values.forEach {
                val manager = it.second
                for (componentPeer in HashSet(manager.circuitBoard.components)) {
                    if (componentPeer is SubcircuitPeer) {
                        if (componentPeer.component.subcircuit == circuit) {
                            val node = getSubcircuitStates(componentPeer.component, manager.circuitBoard.currentState)
                            manager.selectedElements.remove(componentPeer)
                            if (component == null) {
                                manager.mayThrow { manager.circuitBoard.removeElements(hashSetOf(componentPeer)) }
                                resetSubcircuitStates(node)
                            } else {
                                val newSubcircuit =
                                    SubcircuitPeer(componentPeer.properties, componentPeer.x, componentPeer.y)
                                editHistory.disable()
                                manager.mayThrow { manager.circuitBoard.updateComponent(componentPeer, newSubcircuit) }
                                editHistory.enable()
                                node.subcircuit = newSubcircuit.component
                                updateSubcircuitStates(node, manager.circuitBoard.currentState)
                            }
                        }
                    }
                }
            }
        } else if (component is Subcircuit && !added) {
            val node = getSubcircuitStates(component, getCircuitManager(circuit)!!.circuitBoard.currentState)
            resetSubcircuitStates(node)
        }
    }

    private fun getSubcircuitStates(subcircuit: Subcircuit, parentState: CircuitState): CircuitNode {
        val subcircuitState = subcircuit.getSubcircuitState(parentState)
        val circuitNode = CircuitNode(subcircuit, subcircuitState)
        if (subcircuitState == null) return circuitNode

        for (component in subcircuit.subcircuit.components) {
            if (component is Subcircuit)
                circuitNode.children.add(getSubcircuitStates(component, subcircuitState))
        }
        return circuitNode
    }

    private fun updateSubcircuitStates(node: CircuitNode, parentState: CircuitState) {
        val manager = getCircuitManager(node.subcircuit.subcircuit)
        val substate = node.subcircuit.getSubcircuitState(parentState)!!
        if (manager != null && manager.circuitBoard.currentState == node.subcircuitState) {
            manager.switchToCircuitState(substate)
        }
        node.children.forEach { updateSubcircuitStates(it, substate) }
    }

    private fun resetSubcircuitStates(node: CircuitNode) {
        val manager = getCircuitManager(node.subcircuit.subcircuit)
        if (manager != null && manager.circuitBoard.currentState == node.subcircuitState)
            manager.switchToCircuitState()
        node.children.forEach { resetSubcircuitStates(it) }
    }

    private fun checkUnsavedChanges(): Boolean {
        clearSelection()
        if (editHistory.editStackSize() != savedEditStackSize) {
            val alert = Alert(AlertType.CONFIRMATION)
            alert.initOwner(stage)
            alert.initModality(Modality.WINDOW_MODAL)
            alert.title = "Unsaved Changes"
            alert.headerText = "Unsaved Changes"
            alert.contentText = "There are unsaved changes, do you want to save them?"
            val discard = ButtonType("Discard", ButtonBar.ButtonData.NO)
            alert.buttonTypes.add(discard)
            val result = alert.showAndWait().orElse(null)
            result?.let {
                if (it == ButtonType.OK) {
                    saveCircuitsInternal()
                    return saveFile == null
                } else return it == ButtonType.CANCEL
            }
        }
        return false
    }

    /**
     * Clears and destroys all circuits. No tabs or circuits will exist after this.
     */
    fun clearCircuits() = runFxSync {
        Clock.reset(simulator)
        clockEnabled.isSelected = false

        editHistory.disable()
        circuitManagers.forEach { it.value.second.destroy() }
        editHistory.enable()

        circuitManagers.clear()
        canvasTabPane.tabs.clear()
        simulator.clear()

        editHistory.clear()
        savedEditStackSize = 0
        saveFile = null

        undo.isDisable = true
        redo.isDisable = true

        updateTitle()
        refreshCircuitsTab()
    }

    fun copySelectedComponents() {
        val manager = currentCircuit ?: return
        val selected = manager.selectedElements
        if (selected.isEmpty()) return
        val components = selected.filterIsInstance<ComponentPeer<*>>()
            .map { ComponentInfo(it.javaClass.name, it.x, it.y, it.properties) }
        val wires = selected.filterIsInstance<Wire>().map {
            WireInfo(it.x, it.y, it.length, it.isHorizontal)
        }
        try {
            val data = stringify(
                CircuitFile(
                    0, 0, null,
                    mutableListOf(CircuitInfo("Copy", components, wires)),
                    revisionSignatures, copiedBlocks
                )
            )
            val clipboard = Clipboard.getSystemClipboard()
            val content = ClipboardContent()
            content[copyDataFormat] = data
            clipboard.setContent(content)
        } catch (e: Exception) {
            lastException = e
            debugUtil.logException(e, "Error while copying")
        }
    }

    fun cutSelectedComponents() {
        val manager = currentCircuit ?: return
        val selected = manager.selectedElements
        copySelectedComponents()
        manager.mayThrow { manager.circuitBoard.finalizeMove() }
        manager.mayThrow { manager.circuitBoard.removeElements(selected) }
        clearSelection()
        needsRepaint = true
    }

    fun pasteFromClipboard() {
        val clipboard = Clipboard.getSystemClipboard()
        val data = clipboard.getContent(copyDataFormat) as String? ?: return
        try {
            editHistory.beginGroup()
            val parsed = FileFormat.parse(data) ?: return
            if (parsed.revisionSignatures.isNotEmpty() && parsed.revisionSignatures.last() !in revisionSignatures && parsed.revisionSignatures.last() !in copiedBlocks) {
                copiedBlocks.add(parsed.revisionSignatures[0])
                copiedBlocks.add(parsed.revisionSignatures.random())
                copiedBlocks.add(parsed.revisionSignatures.last())
            }

            val manager = currentCircuit ?: return
            var i = 0
            outer@ while (true) {
                val elementsCreated = HashSet<GuiElement>()
                for (circuit in parsed.circuits) {
                    for (component in circuit.components) {
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val clazz = Class.forName(component.name) as Class<out ComponentPeer<*>>
                            val properties = Properties(CircuitSimVersion(parsed.version))
                            component.properties.forEach { key, value ->
                                properties.setProperty(Property(key, null, value))
                            }
                            val creator = if (clazz == SubcircuitPeer::class.java)
                                getSubcircuitPeerCreator(properties.getValueOrDefault(SubcircuitPeer.SUBCIRCUIT, ""))
                            else componentManager.get(clazz, properties).creator
                            val created = creator.createComponent(properties, component.x + i, component.y + i)
                            if (!manager.circuitBoard.isValidLocation(created)) {
                                elementsCreated.clear()
                                i += 3
                                continue@outer
                            }
                            elementsCreated.add(created)
                        } catch (e: SimulationException) {
                            e.printStackTrace()
                            lastException = e
                        } catch (e: Exception) {
                            lastException = e
                            debugUtil.logException(e, "Error loading component ${component.name}")
                        }
                    }
                }

                simulator.runSync {
                    manager.circuitBoard.finalizeMove()
                    editHistory.disable()
                    elementsCreated.forEach { element ->
                        manager.mayThrow { manager.circuitBoard.addComponent(element as ComponentPeer<*>, false) }
                    }
                    manager.circuitBoard.removeElements(elementsCreated, false)
                    editHistory.enable()

                    for (circuit in parsed.circuits) {
                        for (wire in circuit.wires) {
                            elementsCreated.add(Wire(null, wire.x + i, wire.y + i, wire.length, wire.isHorizontal))
                        }
                    }
                    manager.selectedElements = elementsCreated
                    manager.mayThrow { manager.circuitBoard.initMove(elementsCreated, false) }
                }
                break
            }
        } catch (e: SimulationException) {
            e.printStackTrace()
            lastException = e
        } catch (e: Exception) {
            lastException = e
            debugUtil.logException(e, "Error while pasting")
        } finally {
            editHistory.endGroup()
            needsRepaint = true
        }
    }

    private fun loadConfFile() {
        var showHelp = true
        val home = System.getProperty("user.home")
        val file = File(home, ".circuitsim")
        if (file.exists()) {
            val newWindow = parameters == null
            try {
                val lines = file.readLines()
                for (line in lines) {
                    var trim = line.trim()
                    if (trim.isEmpty() || trim[0] == '#') continue
                    val comment = trim.indexOf('#')
                    if (comment != -1)
                        trim = trim.substring(0, comment).trim()
                    val idx = trim.indexOf('=')
                    if (idx == -1) continue

                    val key = trim.substring(0, idx).trim()
                    val value = trim.substring(idx + 1).trim()

                    when (key) {
                        "WindowX" -> try {
                            stage.x = max(value.toDouble() + if (newWindow) 20 else 0, 0.0)
                        } catch (_: NumberFormatException) {
                        }

                        "WindowY" -> try {
                            stage.y = max(value.toDouble() + if (newWindow) 20 else 0, 0.0)
                        } catch (_: NumberFormatException) {
                        }

                        "WindowWidth" -> stage.width = value.toDouble()
                        "WindowHeight" -> stage.height = value.toDouble()
                        "IsMaximized" -> if (!newWindow) stage.isMaximized = value.toBoolean()
                        "Scale" -> scaleFactor = value.toDouble()
                        "LastSavePath" -> lastSaveFile = File(value)
                        "HelpShown" -> if (value == VERSION) showHelp = false
                    }

                }
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: Exception) {
                debugUtil.logException(e, "Error loading configuration file: $file")
            }
        }
        if (openWindow && showHelp) {
            help.fire()
        }
    }

    private fun saveConfFile() {
        if (!openWindow) return

        val home = System.getProperty("user.home")
        val file = File(home, ".circuitsim")

        val conf = ArrayList<String>()
        if (stage.isMaximized) conf.add("IsMaximized=true")
        else {
            conf.add("WindowX=${stage.x}")
            conf.add("WindowY=${stage.y}")
            conf.add("WindowWidth=${stage.width}")
            conf.add("WindowHeight=${stage.height}")
        }
        conf.add("Scale=$scaleFactor")
        conf.add("HelpShown=$VERSION")
        lastSaveFile?.let { conf.add("LastSavePath=${it.absolutePath}") }

        try {
            Files.write(file.toPath(), conf)
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun loadCircuitsInternal(file: File?) {
        val errorMessage = try {
            loadCircuits(file)
            null
        } catch (e: Exception) {
            when (e) {
                is ClassNotFoundException -> "Could not find class:\n${e.message}"
                is JsonSyntaxException -> "Could not parse file:\n${e.cause?.message}"
                is IOException, is NullPointerException, is IllegalArgumentException, is IllegalStateException -> {
                    e.printStackTrace()
                    "Error: ${e.message}"
                }

                else -> {
                    e.printStackTrace()
                    val stream = ByteArrayOutputStream()
                    e.printStackTrace(PrintStream(stream))
                    stream.toString()
                }
            }
        } ?: return
        val alert = Alert(AlertType.ERROR)
        alert.initOwner(stage)
        alert.initModality(Modality.WINDOW_MODAL)
        alert.title = "Error loading circuits"
        alert.headerText = "Error loading circuits"
        alert.dialogPane.content = TextArea(errorMessage)
        alert.showAndWait()
    }

    /**
     * Load the circuits from the specified File. This File is saved for reuse with saveCircuits().
     * If null is passed in, a FileChooser dialog pops up to select a file.
     *
     * @param file The File instance to load the circuits from.
     */
    fun loadCircuits(file: File?) {
        val loadFileLatch = CountDownLatch(1)

        runFxSync {
            var file = file
            if (file == null) {
                val chooser = FileChooser()
                chooser.title = "Open Circuit"
                chooser.extensionFilters.add(FileChooser.ExtensionFilter("Circuit Files", "*.sim"))
                chooser.initialDirectory = lastSaveFile?.parentFile ?: File(System.getProperty("user.home"))
                chooser.initialFileName = lastSaveFile?.name ?: "untitled"
                chooser.selectedExtensionFilter = chooser.extensionFilters[0]
                file = chooser.showOpenDialog(stage)
            }
            if (file == null) {
                loadFileLatch.countDown()
                return@runFxSync
            }

            val bar = ProgressBar()
            val dialog = Dialog<ButtonType>()
            dialog.initOwner(stage)
            dialog.initModality(Modality.WINDOW_MODAL)
            dialog.title = "Loading ${file.name}..."
            dialog.headerText = "Loading ${file.name}..."
            dialog.contentText = "Parsing file..."
            dialog.graphic = bar

            lastSaveFile = file

            val localThread = Thread {
                try {
                    loadingFile = true
                    editHistory.disable()
                    val circuitFile = FileFormat.load(lastSaveFile, debugMode)
                    revisionSignatures.clear()
                    revisionSignatures.addAll(circuitFile.revisionSignatures ?: emptyList())
                    clearCircuits()
                    Platform.runLater {
                        bar.progress = 0.1
                        dialog.contentText = "Creating circuits..."
                    }

                    var totalComponents = 0
                    for (circuit in circuitFile.circuits) {
                        check(!circuitManagers.containsKey(circuit.name)) { "Duplicate circuit names not allowed." }
                        createCircuit(circuit.name)
                        totalComponents += circuit.components.size + circuit.wires.size
                    }
                    Platform.runLater { dialog.contentText = "Creating components..." }
                    val runnables = ArrayDeque<() -> Unit>()
                    val latch = CountDownLatch(totalComponents + 1)
                    val increment = (1.0 - bar.progress) / totalComponents
                    for (circuit in circuitFile.circuits) {
                        val manager = getCircuitManager(circuit.name)
                        for (component in circuit.components) {
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val clazz = Class.forName(component.name) as Class<out ComponentPeer<*>>
                                val properties = Properties(CircuitSimVersion(circuitFile.version))
                                component.properties.forEach { key, value ->
                                    properties.setProperty(Property(key, null, value))
                                }
                                val creator = if (clazz == SubcircuitPeer::class.java)
                                    getSubcircuitPeerCreator(
                                        properties.getValueOrDefault(
                                            SubcircuitPeer.SUBCIRCUIT,
                                            ""
                                        )
                                    )
                                else componentManager.get(clazz, properties).creator

                                runnables.add {
                                    manager!!.mayThrow {
                                        manager.circuitBoard.addComponent(
                                            creator.createComponent(
                                                properties,
                                                component.x,
                                                component.y
                                            )
                                        )
                                    }
                                    bar.progress += increment
                                    latch.countDown()
                                }
                            } catch (e: SimulationException) {
                                e.printStackTrace()
                                exceptionThrown = e
                                latch.countDown()
                            } catch (e: Exception) {
                                exceptionThrown = e
                                debugUtil.logException(e, "Error loading component ${component.name}")
                                latch.countDown()
                            }
                        }
                        for (wire in circuit.wires) {
                            runnables.add {
                                manager!!.mayThrow {
                                    manager.circuitBoard.addWire(
                                        wire.x,
                                        wire.y,
                                        wire.length,
                                        wire.isHorizontal
                                    )
                                }
                                bar.progress += increment
                                latch.countDown()
                            }
                        }
                    }

                    val comps = totalComponents
                    val taskThread = Thread {
                        val maxRunLater = max(comps / 20, 50)
                        while (runnables.isNotEmpty()) {
                            val left = min(runnables.size, maxRunLater)
                            val l = CountDownLatch(left)
                            repeat(left) {
                                val r = runnables.poll()
                                Platform.runLater {
                                    try {
                                        r()
                                    } finally {
                                        l.countDown()
                                    }
                                }
                            }

                            try {
                                l.await()
                            } catch (_: Exception) {
                            }
                        }
                        Platform.runLater {
                            frequenciesMenu.items.forEach { item ->
                                if (item.text.startsWith(circuitFile.clockSpeed.toString())) {
                                    (item as RadioMenuItem).isSelected = true
                                }
                            }
                            if (circuitFile.globalBitSize >= 1 && circuitFile.globalBitSize <= 32)
                                bitSizeSelect.selectionModel.select(circuitFile.globalBitSize - 1)

                            latch.countDown()
                        }
                    }
                    taskThread.name = "LoadCircuits Tasks Thread"
                    taskThread.start()
                    latch.await()
                    saveFile = lastSaveFile
                } catch (e: Exception) {
                    clearCircuits()
                    exceptionThrown = e
                } finally {
                    if (circuitManagers.isEmpty()) createCircuit("New circuit")
                    editHistory.enable()
                    loadingFile = false
                    runFxSync {
                        updateTitle()
                        refreshCircuitsTab()
                        dialog.result = ButtonType.OK
                        dialog.close()
                        loadFileLatch.countDown()
                    }
                }
            }
            localThread.name = "LoadCircuits"
            localThread.start()

            if (openWindow) dialog.showAndWait()
        }

        try {
            loadFileLatch.await()
        } catch (_: Exception) {
        }
        saveConfFile()
        exceptionThrown?.let {
            exceptionThrown = null
            throw it
        }
    }

    private fun saveCircuitsInternal() {
        try {
            saveCircuits()
        } catch (e: Exception) {
            e.printStackTrace()
            val alert = Alert(AlertType.ERROR)
            alert.initOwner(stage)
            alert.initModality(Modality.WINDOW_MODAL)
            alert.title = "Error"
            alert.headerText = "Error saving circuit."
            alert.contentText = "Error when saving the circuit: ${e.message}"
            alert.showAndWait()
        }
    }

    /**
     * Save the circuits to the specified File. This File is saved for reuse with saveCircuits().
     * If null is passed in, a FileChooser dialog pops up to select a file.
     *
     * @param file The File instance to save the circuits to.
     */
    fun saveCircuits(file: File? = saveFile) {
        runFxSync {
            var file = file
            if (file == null) {
                val chooser = FileChooser()
                chooser.title = "Choose sim file"
                chooser.initialDirectory = lastSaveFile?.parentFile ?: File(System.getProperty("user.dir"))
                chooser.initialFileName = "My circuit.sim"
                chooser.extensionFilters.add(FileChooser.ExtensionFilter("Circuit Sim file", "*.sim"))
                file = chooser.showSaveDialog(stage) ?: return@runFxSync
            }
            lastSaveFile = file
            val circuits = ArrayList<CircuitInfo>()
            canvasTabPane.tabs.forEach { tab ->
                val name = tab.text
                val manager = circuitManagers[name]!!.second
                val components = manager.circuitBoard.components.map {
                    ComponentInfo(it.javaClass.name, it.x, it.y, it.properties)
                }.sortedBy { it.hashCode() }
                val wires = manager.circuitBoard.links.flatMap { it.wires }.map {
                    WireInfo(it.x, it.y, it.length, it.isHorizontal)
                }.sortedBy { it.hashCode() }
                circuits.add(CircuitInfo(name, components, wires))
            }

            try {
                FileFormat.save(
                    file,
                    CircuitFile(
                        bitSizeSelect.selectionModel.selectedItem,
                        currentClockSpeed,
                        null,
                        circuits,
                        revisionSignatures,
                        copiedBlocks
                    )
                )
                copiedBlocks.clear()
                savedEditStackSize = editHistory.editStackSize()
                saveFile = file
                updateTitle()
            } catch (e: Exception) {
                e.printStackTrace()
                exceptionThrown = e
            }
        }

        saveConfFile()
        exceptionThrown?.let {
            exceptionThrown = null
            throw it
        }
    }

    private fun <E : Event> onCurrentCircuit(handler: (CircuitManager, E) -> Unit) = EventHandler<E> {
        val manager = this.currentCircuit ?: return@EventHandler
        handler(manager, it)
    }

    /**
     * Create a Circuit, adding a new tab at the end and a button in the Circuits components tab.
     *
     * @param name The name of the circuit and tab.
     */
    fun createCircuit(name: String) {
        if (name.isEmpty()) throw NullPointerException("Name cannot be empty")

        runFxSync {

            // If the name already exists, add a number to the name until it doesn't exist.
            val originalName = name
            var revisedName = name
            var count = 1
            while (getCircuitManager(revisedName) != null) {
                revisedName = "$originalName $count"
                count++
            }

            val canvasTab = Tab(revisedName)
            val circuitManager = CircuitManager(revisedName, this, simulator, showGridProp, canvasTab)
            circuitManager.circuit.addListener(Circuit.CircuitChangeListener(this::circuitModified))

            canvasTab.styleClass.add("top-level-indicator")
            val rename = MenuItem("Rename")
            rename.onAction = EventHandler {
                var lastTyped = canvasTab.text
                while (true) {
                    try {
                        val dialog = TextInputDialog(lastTyped)
                        dialog.title = "Rename circuit"
                        dialog.headerText = "Rename circuit"
                        dialog.contentText = "Enter new name:"
                        val value = dialog.showAndWait().orElse(null) ?: break
                        lastTyped = value.trim()
                        if (lastTyped.isNotEmpty() && lastTyped != canvasTab.text) {
                            renameCircuit(canvasTab, lastTyped)
                            clearSelection()
                        }
                        break
                    } catch (e: Exception) {
                        e.printStackTrace()

                        val alert = Alert(AlertType.ERROR)
                        alert.initOwner(stage)
                        alert.initModality(Modality.WINDOW_MODAL)
                        alert.title = "Duplicate name"
                        alert.headerText = "Duplicate name"
                        alert.contentText = "Name already exists, please choose a new name."
                        alert.showAndWait()
                    }
                }
            }
            val viewTopLevelState = MenuItem("View top-level state")
            viewTopLevelState.onAction = EventHandler {
                circuitManager.switchToCircuitState()
            }

            val moveLeft = MenuItem("Move left")
            moveLeft.onAction = EventHandler {
                val tabs = canvasTabPane.tabs
                val idx = tabs.indexOf(canvasTab)
                if (idx > 0) {
                    tabs.removeAt(idx)
                    tabs.add(idx - 1, canvasTab)
                    canvasTabPane.selectionModel.select(canvasTab)
                    editHistory.addAction(MoveCircuit(circuitManager, tabs, canvasTab, idx, idx - 1))
                    refreshCircuitsTab()
                }
            }

            val moveRight = MenuItem("Move right")
            moveRight.onAction = EventHandler {
                val tabs = canvasTabPane.tabs
                val idx = tabs.indexOf(canvasTab)
                if (idx >= 0 && idx < tabs.size - 1) {
                    tabs.removeAt(idx)
                    tabs.add(idx + 1, canvasTab)
                    canvasTabPane.selectionModel.select(canvasTab)
                    editHistory.addAction(MoveCircuit(circuitManager, tabs, canvasTab, idx, idx + 1))
                    refreshCircuitsTab()
                }
            }

            canvasTab.contextMenu = ContextMenu(rename, viewTopLevelState, moveLeft, moveRight)
            canvasTab.onCloseRequest = EventHandler { event: Event ->
                if (!confirmAndDeleteCircuit(circuitManager, false)) {
                    event.consume()
                }
            }

            circuitManagers[canvasTab.text] = Pair(createSubcircuitLauncherInfo(revisedName), circuitManager)
            canvasTabPane.tabs.add(canvasTab)

            refreshCircuitsTab()

            editHistory.addAction(CreateCircuit(circuitManager, canvasTab, canvasTabPane.tabs.size - 1))
            this.circuitCanvas.requestFocus()
        }
    }

    /**
     * Inline Hyperlink node helper. Creates a JavaFX Hyperlink node with one function call.
     * @param font the font to use
     * @param text the text contents
     * @param url the URL to link to
     * @return a new Hyperlink node
     */
    private fun createHyperlink(font: Font, text: String, url: String): Hyperlink {
        val link = Hyperlink(text)
        link.font = font
        link.onAction = EventHandler { this.hostServices.showDocument(url) }
        return link
    }

    private fun exportAsImages() {
        val chooser = DirectoryChooser()
        chooser.title = "Choose output directory"
        val outputDirectory = chooser.showDialog(stage) ?: return
        val count = circuitManagers.size
        val progressPerCircuit = 1.0 / count
        val bar = ProgressBar(0.0)
        val dialog = Dialog<ButtonType>()
        dialog.initOwner(stage)
        dialog.initModality(Modality.WINDOW_MODAL)
        dialog.title = "Exporting images"
        dialog.headerText = "Exporting images"
        dialog.contentText = "Exporting images"
        dialog.graphic = bar
        dialog.show()
        Thread {
            try {
                val images = HashMap<String, RenderedImage>()
                runFxSync {
                    simulator.runSync {
                        circuitManagers.forEach { (name, pair) ->
                            val manager = pair.second
                            val bounds = manager.circuitBounds
                            manager.translateOrigin =
                                Point2D(bounds.minX, bounds.minY).multiply(-GuiUtils.BLOCK_SIZE.toDouble())
                            scaleFactor = 1.0
                            try {
                                circuitCanvas.width = bounds.width * scaleFactor * GuiUtils.BLOCK_SIZE
                                circuitCanvas.height = bounds.height * scaleFactor * GuiUtils.BLOCK_SIZE
                            } catch (_: Exception) {
                                System.err.println("Could not export circuit $name")
                                return@forEach
                            }
                            manager.paint(circuitCanvas)
                            val image = circuitCanvas.snapshot(null, null)
                            val rendered = SwingFXUtils.fromFXImage(image, null)
                            images[name] = rendered
                            manager.translateOrigin = Point2D.ZERO
                        }
                        updateCanvasSize()
                    }
                }
                val counter = AtomicInteger(0)
                canvasTabPane.tabs.forEach { tab ->
                    val name = tab.text
                    if (!images.containsKey(name)) {
                        System.err.println("Missing image for $name")
                        return@forEach
                    }
                    Platform.runLater { dialog.contentText = "Exporting tab: $name" }
                    val fileName = String.format("%02d-%s.png", counter.getAndIncrement(), name)
                    val file = File(outputDirectory, fileName)
                    if (file.exists()) {
                        val alreadyExistsDecision: AtomicReference<ButtonType?> =
                            AtomicReference(null)
                        val latch = CountDownLatch(1)

                        Platform.runLater {
                            try {
                                val alert = Alert(
                                    AlertType.CONFIRMATION,
                                    "Overwrite existing file? $fileName",
                                    ButtonType.OK,
                                    ButtonType.CANCEL
                                )
                                alert.initOwner(stage)
                                alert.title = "File already exists"
                                alert.headerText = "File already exists"
                                val buttonType = alert.showAndWait().orElse(null)
                                alreadyExistsDecision.set(buttonType)
                            } finally {
                                latch.countDown()
                            }
                        }

                        while (latch.count > 0) try {
                            latch.await()
                        } catch (_: InterruptedException) {
                        }

                        if (alreadyExistsDecision.get() != ButtonType.OK) return@forEach
                    }
                    try {
                        ImageIO.write(images[name], "png", file)
                    } catch (e: Exception) {
                        System.err.println("Error writing $fileName:")
                        e.printStackTrace()
                    } finally {
                        Platform.runLater { bar.progress += progressPerCircuit }
                    }
                }
            } finally {
                Platform.runLater {
                    dialog.result = ButtonType.OK
                    dialog.close()
                }
            }
        }.start()
    }

    override fun start(primaryStage: Stage) {
        val canvas = Canvas(800.0, 400.0)
        startContext = StartContext(
            primaryStage,
            canvas,
            CheckMenuItem("Simulation Enabled"),
            ToggleButton("Click Mode (Shift)"),
            Slider(),
            ComboBox(),
            Accordion(),
            ToggleGroup(),
            Label(),
            Label(),
            Label(),
            GridPane(),
            Label(),
            ScrollPane(canvas),
            TabPane(),
        )

        // App Icon
        //    (Windows & Linux)
        stage.icons.add(Image(javaClass.getResourceAsStream("/images/Icon.png")!!))
        //     (MacOS)
        if (Taskbar.isTaskbarSupported()) {
            val taskbar = Taskbar.getTaskbar()
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                val defaultToolkit = Toolkit.getDefaultToolkit()
                taskbar.iconImage = defaultToolkit.getImage(javaClass.getResource("/images/IconMacOS.png"))
            }
        }

        for (i in 1..32) bitSizeSelect.items.add(i)
        bitSizeSelect.value = 1

        bitSizeSelect.selectionModel.selectedItemProperty().addListener { _, _, _ ->
            modifiedSelection(selectedComponent)
        }

        scaleFactorSlider.min = 0.25
        scaleFactorSlider.max = 4.0
        scaleFactorSlider.value = 1.0
        scaleFactorSlider.isShowTickLabels = false
        scaleFactorSlider.isShowTickMarks = false
        scaleFactorSlider.maxWidth = 120.0
        scaleFactorSlider.valueProperty().addListener { _, _, _ -> needsRepaint = true }

        componentLabel.font = getFont(16)
        canvasScrollPane.isFocusTraversable = true
        circuitCanvas.isFocusTraversable = true
        fun <T : InputEvent> addHandler(eventType: EventType<T>, handler: (CircuitManager, T) -> Unit) {
            circuitCanvas.addEventHandler(eventType, onCurrentCircuit(handler))
        }

        val mouseHandlers = arrayOf(
            MouseEvent.MOUSE_MOVED to CircuitManager::mouseMoved,
            MouseEvent.MOUSE_RELEASED to CircuitManager::mouseReleased,
            MouseEvent.MOUSE_DRAGGED to CircuitManager::mouseDragged,
            MouseEvent.MOUSE_ENTERED to CircuitManager::mouseEntered,
            MouseEvent.MOUSE_EXITED to CircuitManager::mouseExited
        )
        val scrollHandlers = arrayOf(
            ScrollEvent.SCROLL_STARTED to CircuitManager::scrollStarted,
            ScrollEvent.SCROLL to CircuitManager::scroll,
            ScrollEvent.SCROLL_FINISHED to CircuitManager::scrollFinished
        )
        val keyHandlers = arrayOf(
            KeyEvent.KEY_PRESSED to CircuitManager::keyPressed,
            KeyEvent.KEY_TYPED to CircuitManager::keyTyped,
            KeyEvent.KEY_RELEASED to CircuitManager::keyReleased
        )
        for (handler in mouseHandlers) addHandler(handler.first, handler.second)
        for (handler in scrollHandlers) addHandler(handler.first, handler.second)
        for (handler in keyHandlers) addHandler(handler.first, handler.second)
        circuitCanvas.addEventHandler(MouseEvent.ANY) { circuitCanvas.requestFocus() }
        circuitCanvas.addEventHandler(MouseEvent.MOUSE_DRAGGED) { currentCircuit?.mouseDragged(it) }
        circuitCanvas.addEventHandler(MouseEvent.MOUSE_PRESSED) { currentCircuit?.mousePressed(it) }
        circuitCanvas.addEventHandler(ZoomEvent.ZOOM, onCurrentCircuit(CircuitManager::zoom))
        circuitCanvas.onContextMenuRequested = onCurrentCircuit(CircuitManager::contextMenuRequested)

        canvasScrollPane.widthProperty().addListener { _, _, _ -> updateCanvasSize() }
        canvasScrollPane.heightProperty().addListener { _, _, _ -> updateCanvasSize() }

        canvasTabPane.prefWidth = 800.0
        canvasTabPane.prefHeight = 600.0
        canvasTabPane.tabDragPolicy = TabPane.TabDragPolicy.REORDER
        canvasTabPane.tabClosingPolicy = TabPane.TabClosingPolicy.ALL_TABS
        canvasTabPane.widthProperty().addListener { _, _, _ -> needsRepaint = true }
        canvasTabPane.heightProperty().addListener { _, _, _ -> needsRepaint = true }
        canvasTabPane.selectionModel.selectedItemProperty().addListener { _, old, new ->
            old?.content = null
            new?.content = canvasScrollPane
            val oldManager = old?.let { circuitManagers[it.text]?.second } ?: return@addListener
            val newManager = new?.let { circuitManagers[it.text]?.second } ?: return@addListener
            newManager.lastMousePosition = oldManager.lastMousePosition
            modifiedSelection(selectedComponent)
            needsRepaint = true
        }

        val buttonTabs = HashMap<String, TitledPane>()
        componentManager.forEach { componentInfo ->
            if (!componentInfo.showInComponentsList) return@forEach
            var section = buttonTabs[componentInfo.name.first]
            if (section == null) {
                val flowPane = FlowPane(Orientation.HORIZONTAL).apply {
                    hgap = 10.0
                    vgap = 10.0
                    prefHeight = Region.USE_COMPUTED_SIZE
                    minHeight = Region.USE_COMPUTED_SIZE
                    maxHeight = Region.USE_COMPUTED_SIZE
                }
                val pane = VBox(flowPane).apply {
                    prefHeightProperty().bind(flowPane.heightProperty())
                }
                section = TitledPane(componentInfo.name.first, pane)
                section.styleClass.add("new-component-section")
                if (buttonTabPane.panes.isEmpty()) {
                    section.styleClass.add("titled-pane-first")
                }
                buttonTabPane.panes.add(section)
                buttonTabs[componentInfo.name.first] = section
            }
            val buttons = (section.content as VBox).children[0] as FlowPane
            val toggleButton = setupButton(buttonsToggleGroup, componentInfo)
            VBox.setMargin(toggleButton, Insets(20.0, 10.0, 20.0, 10.0))
            buttons.children.add(toggleButton)
        }
        buttonTabPane.styleClass.add("button-tab-pane")
        circuitButtonsTab = null
        refreshCircuitsTab()

        editHistory.disable()
        createCircuit("New circuit")
        editHistory.enable()

        editHistory.addListener {
            undo.isDisable = editHistory.editStackSize() == 0
        }
        editHistory.addListener {
            redo.isDisable = editHistory.redoStackSize() == 0
        }

        Clock.clockEnabledProperty(simulator).addListener { _, _, new -> clockEnabled.isSelected = new.enabled }

        simulationEnabled.isSelected = true

        val fParagraph = Font(14.0)
        val fHeader = Font(16.0)

        // Menu Bar
        val menuBar = menuBar {
            menu("File") {
                item("New", KeyCodeCombination(N, SHORTCUT_DOWN)) {
                    saveConfFile()
                    CircuitSim(true, debugMode = debugMode)
                }
                item("Clear") {
                    if (!checkUnsavedChanges()) {
                        clearCircuits()
                        editHistory.disable()
                        createCircuit("New circuit")
                        editHistory.enable()
                    }
                }
                separator()
                item("Open", KeyCodeCombination(O, SHORTCUT_DOWN)) {
                    if (!checkUnsavedChanges()) loadCircuitsInternal(null)
                }
                item("Save", KeyCodeCombination(S, SHORTCUT_DOWN)) { saveCircuitsInternal() }
                item("Save as", KeyCodeCombination(S, SHORTCUT_DOWN, SHIFT_DOWN)) {
                    lastSaveFile = saveFile
                    saveFile = null
                    saveCircuitsInternal()
                    if (saveFile == null) saveFile = lastSaveFile
                    updateTitle()
                }
                item("Export as images") { exportAsImages() }
                separator()
                item("Exit") { if (!checkUnsavedChanges()) closeWindow() }
            }
            menu("Edit") {
                this@CircuitSim.undo = item("Undo", KeyCodeCombination(Z, SHORTCUT_DOWN), true) {
                    currentCircuit?.selectedElements = HashSet()
                    editHistory.undo()?.let {
                        it.selectedElements = HashSet()
                        switchToCircuit(it.circuit, null)
                    }
                }
                this@CircuitSim.redo = item("Redo", KeyCodeCombination(Y, SHORTCUT_DOWN), true) {
                    currentCircuit?.selectedElements = HashSet()
                    editHistory.redo()?.let {
                        it.selectedElements = HashSet()
                        switchToCircuit(it.circuit, null)
                    }
                }
                separator()
                item("Copy", KeyCodeCombination(C, SHORTCUT_DOWN)) {
                    if (circuitCanvas.isFocused) copySelectedComponents()
                }
                item("Cut", KeyCodeCombination(X, SHORTCUT_DOWN)) {
                    if (circuitCanvas.isFocused) cutSelectedComponents()
                }
                item("Paste", KeyCodeCombination(V, SHORTCUT_DOWN)) {
                    if (circuitCanvas.isFocused) pasteFromClipboard()
                }
                separator()
                item("Select All", KeyCodeCombination(A, SHORTCUT_DOWN)) {
                    val manager = currentCircuit ?: return@item
                    if (!circuitCanvas.isFocused) return@item
                    manager.selectedElements =
                        HashSet(manager.circuitBoard.links.flatMap { it.wires } + manager.circuitBoard.components)
                    needsRepaint = true
                }
            }
            menu("View") {
                checkItem("Show grid", binding = showGridProp) { _, _, _ -> needsRepaint = true }
            }
            menu("Circuits") {
                item("New circuit", KeyCodeCombination(T, SHORTCUT_DOWN)) {
                    createCircuit("New circuit")
                }
                item("Delete circuit", KeyCodeCombination(W, SHORTCUT_DOWN)) {
                    currentCircuit?.let { confirmAndDeleteCircuit(it, true) }
                }
            }
            menu("Simulation") {
                val stepSimulation = item("Step Simulation", KeyCodeCombination(I, SHORTCUT_DOWN), true) {
                    try {
                        simulator.step()
                    } catch (e: Exception) {
                        lastException = e
                    } finally {
                        needsRepaint = true
                    }
                }
                checkItem("Simulation Enabled", KeyCodeCombination(E, SHORTCUT_DOWN), true) { _, _, new ->
                    runSim()
                    stepSimulation.isDisable = new
                    simulationEnabled.isDisable = !new
                    simulationEnabled.isSelected = false
                }
                item("Reset simulation", KeyCodeCombination(R, SHORTCUT_DOWN)) {
                    Clock.reset(simulator)
                    clockEnabled.isSelected = false
                    simulator.reset()
                    for ((_, manager) in circuitManagers.values) {
                        manager.switchToCircuitState()
                    }
                    runSim()
                }
                val tickClock = item("Tick clock", KeyCodeCombination(J, SHORTCUT_DOWN)) { Clock.tick(simulator) }
                this@CircuitSim.clockEnabled =
                    checkItem("Clock Enabled", KeyCodeCombination(K, SHORTCUT_DOWN)) { _, _, new ->
                        tickClock.isDisable = new
                        Clock.clockEnabledProperty(simulator).set(Clock.EnabledInfo(new, currentClockSpeed))
                    }
                this@CircuitSim.frequenciesMenu = radioMenu("Frequency", 15, { RadioMenuItem("${1 shl it} Hz") }) {
                    if (isRunning(simulator)) Clock.startClock(simulator, 1 shl it)
                }
            }
            menu("Help") {
                this@CircuitSim.help = item("Help") {
                    val alert = Alert(AlertType.INFORMATION)
                    alert.initOwner(stage)
                    alert.initModality(Modality.NONE)
                    alert.title = "Help"
                    alert.headerText = VERSION_TAG_LINE

                    val helpTextPane = TextFlow(
                        createText(fHeader, "Editing\n"),
                        createMultilineText(
                            fParagraph,
                            " - Use left pane to access components and edit component properties",
                            " - Drag from a component port or wire to create a wire",
                            " - Drag selected items to move items",
                            " - Ctrl + Click item to toggle-add item to selection",
                            " - Ctrl + Click from a component port or wire to start creating a wire and click again to complete wire",
                            " - Ctrl + Drag selected items to move items without reconnecting items to circuit",
                            " - Ctrl + Place component to \"stamp\"-place a component",
                            " - Shift while dragging wire to delete wires",
                            " - Use default shortcuts (or right-click) to copy, cut, and paste",
                            " - Create subcircuits with [Circuits -> New circuit], and access them with the Circuits tab on the left pane"
                        ),

                        createText(fHeader, "\nSimulating\n"),
                        createMultilineText(
                            fParagraph,
                            "- Start simulation: [Simulation -> Clock Enabled]",
                            "- Simulate a single clock tick: [Simulation -> Tick clock]",
                            "- Double-click a subcircuit to view its state",
                            "- Hold Shift (or click top-left button) to enable Click Mode:",
                            "    - Click wires to view their binary value",
                            "    - Click on inputs to toggle their values",
                            "    - Drag on background to pan on canvas"
                        ),

                        createText(fHeader, "\nViewing\n"),
                        createMultilineText(
                            fParagraph,
                            "- Turn off the grid background with [View -> Show grid]",
                            "- Export circuits as images with [File -> Export as images]",
                            "- You can pan and zoom on the canvas with any of the following methods:",
                            "    - Using trackpad gestures",
                            "    - Scrolling with a mouse wheel to pan vertically and Ctrl + Scroll-ing with a mouse wheel to zoom",
                            "    - Dragging on the background in click mode to pan"
                        )
                    )
                    alert.dialogPane.content = ScrollPane(helpTextPane)

                    alert.show()
                    alert.isResizable = true
                    alert.width = 650.0
                    alert.height = 550.0
                }
                item("About") {
                    val alert = Alert(AlertType.INFORMATION)
                    alert.initOwner(stage)
                    alert.initModality(Modality.WINDOW_MODAL)
                    alert.title = "About"


                    // Add silly factor:
                    val cDescriptors = arrayOf(
                        "Cacophonous", "Carefree", "Casual", "Catastrophic", "Catchy",
                        "Cautious", "Cavernous", "Celestial", "Ceramic", "Certain",
                        "Charismatic", "Cherubic", "Chirping", "Chivalrous", "Chronic",
                        "Citrusy", "Clairvoyant", "Clandestine", "Clever", "Colorful",
                        "Community", "Complex", "Conniving", "Cool", "Corny", "Covetous",
                        "Cozy", "Creative", "Cryogenic", "Cryptic", "Cuddly", "Cynical"
                    )
                    val desc = cDescriptors[(Math.random() * cDescriptors.size).toInt()]
                    alert.headerText = VERSION_TAG_LINE_LONG.format(desc)

                    val javaVersion = System.getProperty("java.version")
                    val javaVendor = System.getProperty("java.vendor")
                    val javaVendorVersion = System.getProperty("java.vendor.version", "?")
                    val jfxVersion = System.getProperty("javafx.runtime.version")
                    val osName = System.getProperty("os.name")
                    val osVersion = System.getProperty("os.version")
                    val osArch = System.getProperty("os.arch")
                    val aboutPane = TextFlow( // Summary text
                        createText(fParagraph, "Originally created by Roi Atalla \u00A9 2022\n"),
                        createText(fParagraph, "Software licensed under the BSD 3-Clause License\n"),
                        createText(fParagraph, "CE Repository can be found on"),
                        createHyperlink(fParagraph, "GitHub", "https://github.com/gt-cs2110/CircuitSim"),
                        createText(fParagraph, "\n"),  // Version text
                        createText(fHeader, "\nVersion info:\n"),
                        createMultilineText(
                            fParagraph,
                            java.lang.String.format("CircuitSim version: %s", VERSION),
                            "Java version: $javaVersion",
                            "JRE vendor: $javaVendor ($javaVendorVersion)",
                            "JavaFX version: $jfxVersion",
                            "Operating system: $osName $osVersion ($osArch)"
                        ),  // Third party tools
                        createText(fHeader, "\nThird party tools used:\n"),
                        createText(fParagraph, " - "),
                        this@CircuitSim.createHyperlink(fParagraph, "GSON by Google", "https://github.com/google/gson")
                    )
                    alert.dialogPane.content = aboutPane
                    alert.show()
                }
            }
        }
        menuBar.isUseSystemMenuBar = true


        propertiesTable.styleClass.add("props-table")
        val propertiesScrollPane = ScrollPane(propertiesTable)
        propertiesScrollPane.isFitToWidth = true

        propertiesScrollPane.styleClass.add("props-menu")
        val propertiesBox = VBox(componentLabel, propertiesScrollPane)
        propertiesBox.alignment = Pos.TOP_CENTER
        VBox.setVgrow(propertiesScrollPane, Priority.ALWAYS)

        val newComponentPane = ScrollPane(buttonTabPane)
        newComponentPane.isFitToWidth = true
        buttonTabPane.maxWidth = Double.MAX_VALUE

        val leftPaneSplit = SplitPane(newComponentPane, propertiesBox)
        leftPaneSplit.orientation = Orientation.VERTICAL
        leftPaneSplit.prefWidth = 600.0
        leftPaneSplit.minWidth = 250.0

        SplitPane.setResizableWithParent(newComponentPane, FALSE)

        fpsLabel.minWidth = 100.0
        fpsLabel.font = getFont(13)
        fpsLabel.alignment = Pos.CENTER_LEFT

        clockLabel.minWidth = 100.0
        clockLabel.alignment = Pos.CENTER_LEFT
        clockLabel.font = getFont(13)

        messageLabel.textFill = Color.RED
        messageLabel.font = getFont(20, true)

        val blank1 = Pane()
        HBox.setHgrow(blank1, Priority.ALWAYS)

        val blank2 = Pane()
        HBox.setHgrow(blank2, Priority.ALWAYS)

        val statusBar = HBox(fpsLabel, clockLabel, blank1, messageLabel, blank2)
        val canvasTabBox = VBox(canvasTabPane, statusBar)
        VBox.setVgrow(canvasTabPane, Priority.ALWAYS)

        val canvasPropsSplit = SplitPane(leftPaneSplit, canvasTabBox)
        canvasPropsSplit.orientation = Orientation.HORIZONTAL
        canvasPropsSplit.setDividerPositions(0.35)

        SplitPane.setResizableWithParent(leftPaneSplit, FALSE)

        val toolbar = ToolBar()
        fun createToolbarButton(pair: Pair<String, String>): ToggleButton {
            val info = componentManager.get(pair)
            val button = ToggleButton("", setupImageView(info.image!!))
            VBox.setMargin(button, Insets(8.0))
            button.styleClass.add("toolbar-button")
            button.tooltip = Tooltip(pair.second)
            button.minWidth = 50.0
            button.minHeight = 50.0
            button.toggleGroup = buttonsToggleGroup
            button.onAction = EventHandler { modifiedSelection(if (button.isSelected) info else null) }
            return button
        }

        val inputPinButton = createToolbarButton(Pair("Wiring", "Input Pin"))
        val outputPinButton = createToolbarButton(Pair("Wiring", "Output Pin"))
        val andButton = createToolbarButton(Pair("Gates", "AND"))
        val orButton = createToolbarButton(Pair("Gates", "OR"))
        val notButton = createToolbarButton(Pair("Gates", "NOT"))
        val xorButton = createToolbarButton(Pair("Gates", "XOR"))
        val tunnelButton = createToolbarButton(Pair("Wiring", "Tunnel"))
        val textButton = createToolbarButton(Pair("Misc", "Text"))

        clickMode.tooltip = Tooltip("Clicking will sticky this mode")
        clickMode.onAction = EventHandler { clickedDirectly = clickMode.isSelected }
        clickMode.selectedProperty().addListener(ChangeListener { _, _, newValue: Boolean ->
            scene.cursor = if (newValue) Cursor.HAND else Cursor.DEFAULT
        })
        clickMode.styleClass.add("button")

        val blank = Pane()
        HBox.setHgrow(blank, Priority.ALWAYS)
        val bitSizeLabel = Label("Global bit size:")
        bitSizeLabel.styleClass.add("bit-size-label")
        bitSizeSelect.styleClass.add("bit-size-dropdown")
        val bitSize = HBox(bitSizeLabel, bitSizeSelect)
        bitSize.styleClass.add("bit-size-box")
        bitSize.setOnMouseClicked { bitSizeSelect.show() }
        bitSizeSelect.setOnShowing {
            bitSize.styleClass.add("showing-dropdown")
        }
        bitSizeSelect.setOnHidden {
            bitSize.styleClass.remove("showing-dropdown")
        }
        toolbar.items.addAll(
            clickMode, Separator(Orientation.VERTICAL),
            inputPinButton, outputPinButton, andButton, orButton,
            notButton, xorButton, tunnelButton, textButton,
            Separator(Orientation.VERTICAL), bitSize,
            blank, Label("Scale:"), scaleFactorSlider
        )

        VBox.setVgrow(canvasPropsSplit, Priority.ALWAYS)
        scene = Scene(VBox(menuBar, toolbar, canvasPropsSplit))
        scene.cursor = Cursor.DEFAULT

        scene.stylesheets.add(javaClass.getResource("/styles/style.css")!!.toExternalForm())

        updateTitle()
        stage.scene = scene
        stage.sizeToScene()
        stage.centerOnScreen()
        // Ctrl+1, +2, +3, ..., +9
        scene.accelerators.put(KeyCodeCombination(DIGIT1, SHORTCUT_DOWN), clickMode::fire)
        scene.accelerators.put(KeyCodeCombination(DIGIT2, SHORTCUT_DOWN), inputPinButton::fire)
        scene.accelerators.put(KeyCodeCombination(DIGIT3, SHORTCUT_DOWN), outputPinButton::fire)
        scene.accelerators.put(KeyCodeCombination(DIGIT4, SHORTCUT_DOWN), andButton::fire)
        scene.accelerators.put(KeyCodeCombination(DIGIT5, SHORTCUT_DOWN), orButton::fire)
        scene.accelerators.put(KeyCodeCombination(DIGIT6, SHORTCUT_DOWN), notButton::fire)
        scene.accelerators.put(KeyCodeCombination(DIGIT7, SHORTCUT_DOWN), xorButton::fire)
        scene.accelerators.put(KeyCodeCombination(DIGIT8, SHORTCUT_DOWN), tunnelButton::fire)
        scene.accelerators.put(KeyCodeCombination(DIGIT9, SHORTCUT_DOWN), textButton::fire)

        if (openWindow) {
            showWindow()

            loadConfFile()
            saveConfFile()

            val parameters = getParameters()
            if (parameters != null) {
                var args = parameters.raw

                if (!args.isEmpty() && args[0] == "--ta-debug") {
                    debugMode = true
                    args = args.stream().skip(1).collect(Collectors.toList())
                }

                if (!args.isEmpty()) {
                    loadCircuitsInternal(File(args[0]))

                    if (args.size > 1) {
                        for (i in 1..<args.size) {
                            CircuitSim(true, debugMode = debugMode).loadCircuitsInternal(File(args[i]))
                        }
                    }
                }
            }
        }
    }

    fun showWindow() = runFxSync {
        if (stage.isShowing) return@runFxSync
        stage.show()
        stage.onCloseRequest = EventHandler {
            if (checkUnsavedChanges()) it.consume()
            else saveConfFile()
        }
        currentTimer = object : AnimationTimer() {
            private var lastRepaint = 0L
            private var frameCount = 0

            override fun handle(now: Long) {
                if (now - lastRepaint >= 1e9) {
                    val lastFrameCount = frameCount
                    frameCount = 0
                    lastRepaint = now

                    fpsLabel.text = "FPS: $lastFrameCount"
                    clockLabel.text =
                        if (isRunning(simulator)) "Clock: ${getLastTickCount(simulator) shr 1} Hz"
                        else ""
                }

                frameCount++
                runSim()

                val manager = currentCircuit ?: return
                if (needsRepaint) {
                    needsRepaint = false
                    simulator.runSync { manager.paint(circuitCanvas) }
                }

                if (!loadingFile) {
                    val message = currentError
                    if (!message.isEmpty() && isRunning(simulator)) clockEnabled.isSelected = false
                    if (messageLabel.text != message) messageLabel.text = message
                } else if (!messageLabel.text.isEmpty())
                    messageLabel.text = ""
            }
        }.also { it.start() }
    }

    fun closeWindow() = runFxSync {
        stage.close()
        currentTimer?.stop()
        currentTimer = null
    }

    class CircuitNode(var subcircuit: Subcircuit, val subcircuitState: CircuitState?) {
        val children = ArrayList<CircuitNode>()
    }

    companion object {

        private val copyDataFormat: DataFormat = DataFormat("x-circuit-simulator")


        val VERSION = CircuitSimVersion.VERSION.version // for backwards compatibility
        private val VERSION_NO_CE = VERSION.substring(0, VERSION.length - "-CE".length)
        val VERSION_TAG_LINE = "CircuitSim CE v$VERSION_NO_CE"
        val VERSION_TAG_LINE_LONG = "CircuitSim %s Edition v$VERSION_NO_CE"

        const val SHOW_ERROR_DURATION = 3000
        const val SCALE_MIN = 0.25
        const val SCALE_MAX = 8.0

        fun clampScaleFactor(scale: Double) =
            if (scale.isNaN()) 1.0
            else min(max(scale, SCALE_MIN), SCALE_MAX)

        /**
         * Inline Text node helper. Creates a JavaFX Text node with one function call.
         * @param font the font to use
         * @param text the text contents
         * @return a new Text node
         */
        private fun createText(font: Font, text: String): Text {
            val textNode = Text(text)
            textNode.font = font
            return textNode
        }

        /**
         * Inline Text node helper. Creates a JavaFX Text node with multiple lines with one function call.
         * @param font the font to use
         * @param text the content of the lines
         * @return a new Text node
         */
        private fun createMultilineText(font: Font, vararg text: String) =
            createText(font, text.joinToString("\n", postfix = "\n"))

        fun run(args: Array<String>) = launch(CircuitSim::class.java, *args)
        private fun runFxSync(runnable: () -> Unit) {
            if (Platform.isFxApplicationThread()) runnable()
            else {
                val latch = CountDownLatch(1)
                try {
                    Platform.runLater {
                        try {
                            runnable()
                        } finally {
                            latch.countDown()
                        }
                    }
                } catch (e: IllegalStateException) {
                    if (latch.count > 0) {
                        // JavaFX Platform uninitialized
                        Platform.startup {
                            try {
                                runnable()
                            } finally {
                                latch.countDown()
                            }
                        }
                    } else throw e
                }
                try {
                    latch.await()
                } catch (e: InterruptedException) {
                    throw RuntimeException(e)
                }
            }
        }
    }
}