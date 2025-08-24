package com.ra4king.circuitsim.gui

import com.ra4king.circuitsim.simulator.Simulator
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.stage.Stage

data class InitContext(
    val simulator: Simulator,
    val circuitManagers: HashMap<String, Pair<ComponentManager.ComponentLauncherInfo, CircuitManager>>,
    val editHistory: EditHistory,
    val componentManager: ComponentManager
)

data class StartContext(
    val stage: Stage,
    val circuitCanvas: Canvas,
    val simulationEnabled: CheckMenuItem,
    val clickMode: ToggleButton,
    val scaleFactorSlider: Slider,
    val bitSizeSelect: ComboBox<Int>,
    val buttonTabPane: Accordion,
    val buttonsToggleGroup: ToggleGroup,
    val fpsLabel: Label,
    val clockLabel: Label,
    val messageLabel: Label,
    val propertiesTable: GridPane,
    val componentLabel: Label,
    val canvasScrollPane: ScrollPane,
    val canvasTabPane: TabPane,
)