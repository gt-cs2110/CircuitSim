package com.ra4king.circuitsim.gui;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.ra4king.circuitsim.gui.ComponentManager.ComponentCreator;
import com.ra4king.circuitsim.gui.Connection.PortConnection;
import com.ra4king.circuitsim.gui.LinkWires.Wire;
import com.ra4king.circuitsim.gui.Properties.Direction;
import com.ra4king.circuitsim.gui.peers.SubcircuitPeer;
import com.ra4king.circuitsim.simulator.Circuit;
import com.ra4king.circuitsim.simulator.CircuitState;
import com.ra4king.circuitsim.simulator.Port;
import com.ra4king.circuitsim.simulator.Port.Link;
import com.ra4king.circuitsim.simulator.SimulationException;
import com.ra4king.circuitsim.simulator.Simulator;

import javafx.beans.property.BooleanProperty;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.FontSmoothingType;
import javafx.scene.text.Text;
import javafx.util.Pair;

/**
 * Wrapper in charge of the GUI for managing a {@link CircuitBoard}.
 * This class also handles the mouse and keyboard interactions
 * that enable displaying and editing of a given circuit.
 * 
 * <h3>Coordinate Systems</h3>
 * 
 * Due to the nature of this class, there are 3 different coordinate systems involved in its
 * implementation. To reduce redundancy, they will be given the following names:
 * <ol>
 *     <li>
 *         <b>Circuit Coordinate System</b>: An integer coordinate system that increments in tiles.
 *         The position of ports and components are defined in this coordinate system.
 *         This coordinate system is returned by methods such as {@link GuiElement#getX()} and 
 *         {@link GuiElement#getY()} and is stored as two int variables.
 *     </li>
 *     <li>
 *         <b>Canvas Coordinate System</b>: A double coordinate system representing the graphics 
 *         before any transforms (particularly scaling and translating). The graphics are defined
 *         under this coordinate system. Note that 1 tile in the circuit coordinate system 
 *         corresponds to {@link GuiUtils#BLOCK_SIZE} units in the canvas coordinate system.
 *         This coordinate system is returned by methods such as {@link GuiElement#getScreenX()} and
 *         {@link GuiElement#getScreenY} and is stored as two double variables or a {@link Point2D}.
 *     </li>
 *     <li>
 *         <b>Pixel Coordinate System</b>: A double coordinate system representing the graphics
 *         after all transforms (particularly scaling and translating). 1 unit in the pixel coordinate
 *         system physically corresponds to a pixel in the Scene, so any coordinate values from
 *         outside CircuitManager typically will be in this system. This system directly corresponds to
 *         the local coordinate system in JavaFX.
 *         This coordinate system is returned by methods such as {@link Canvas#getWidth()}, 
 *         {@link Canvas#getHeight()}, {@link MouseEvent#getX()}, {@link MouseEvent#getY()}, etc. and 
 *         is stored as two double variables or a {@link Point2D}.
 *     </li>
 * </ol>
 * 
 * @author Roi Atalla
 */
public class CircuitManager {
	private enum SelectingState {
		IDLE,
		BACKGROUND_DRAGGED,
		ELEMENT_SELECTED,
		CONNECTION_SELECTED,
		ELEMENT_DRAGGED,
		CONNECTION_DRAGGED,
		PLACING_COMPONENT,
	}
	
	private SelectingState currentState = SelectingState.IDLE;
	
	private final CircuitSim simulatorWindow;
	private final BooleanProperty showGrid;
	private final CircuitBoard circuitBoard;
	
	/**
	 * The position of the last mouse interaction on the Canvas
	 * under the (untransformed) canvas coordinate space.
	 */
	private Point2D lastMousePosition = new Point2D(0, 0);
	/**
	 * The position of the last mouse press on the Canvas
	 * under the (untransformed) canvas coordinate space.
	 */
	private Point2D lastMousePressed = new Point2D(0, 0);
	/**
	 * The amount to translate the origin in the pixel coordinate space.
	 * 
	 * (0, 0) in canvas coordinate space 
	 * maps to (tx * scale, ty * scale) in pixel coordinate space.
	 */
	private Point2D translateOrigin = new Point2D(0, 0);

	/**
	 * The position of the last mouse press on the Canvas
	 * under pixel coordinate space.
	 * 
	 * This is used for panning.
	 */
	private Point2D lastMousePressedPan = new Point2D(0, 0);
	/**
	 * A temporary value which holds the translate origin on mouse press.
	 * 
	 * This is used for panning.
	 */
	private Point2D translateOriginBeforePan = new Point2D(0, 0);

	private GuiElement lastPressed;
	private boolean lastPressedConsumed;
	private KeyCode lastPressedKeyCode;
	
	private LinkWires inspectLinkWires;
	
	private boolean isMouseInsideCanvas;
	private boolean isDraggedHorizontally;
	private boolean isCtrlDown;
	private boolean isShiftDown;
	/**
	 * A boolean that indicates whether a scroll should zoom or pan.
	 */
	private boolean zoomOnScroll;
	/**
	 * A boolean that indicates whether the user has a trackpad 
	 * (as opposed to a mouse wheel).
	 * 
	 * It is unfortunately difficult to track whether a scroll event 
	 * is done with a trackpad or a scroll wheel while also maintaining trackpad inertia.
	 * 
	 * Note that trackpads trigger the scrollStart/scrollEnd events, while mouse wheels don't.
	 * So, you would think that setting this field in the interval between those two events would work,
	 * but it doesn't because inertial scroll events can happen after scrollEnd.
	 * 
	 * The way this variable is handled is basically:
	 * - set usingTrackpad to true when scrollStart (because we know we're trackpadding there)
	 * - set usingTrackpad to false during an event where scrolling can't happen (because we know scrolling has ended!)
	 * Not perfect, but it'll catch most cases and the case where it fails is super unlikely (right?)
	 */
	private boolean usingTrackpad;

	private final Circuit dummyCircuit = new Circuit("Dummy", new Simulator());
	private ComponentPeer<?> potentialComponent;
	private ComponentCreator<?> componentCreator;
	private Properties potentialComponentProperties;
	
	private Connection startConnection, endConnection;
	
	private final Set<GuiElement> selectedElements = new HashSet<>();
	
	private Exception lastException;
	private long lastExceptionTime;
	private static final long SHOW_ERROR_DURATION = 3000;
	
	CircuitManager(String name, CircuitSim simulatorWindow, Simulator simulator, BooleanProperty showGrid) {
		this.simulatorWindow = simulatorWindow;
		this.showGrid = showGrid;
		circuitBoard = new CircuitBoard(name, this, simulator, simulatorWindow.getEditHistory());
	}
	
	public void setName(String name) {
		circuitBoard.setName(name);
	}
	
	public String getName() {
		return circuitBoard.getName();
	}
	
	@Override
	public String toString() {
		return "CircuitManager of " + getName();
	}
	
	private void resetLastPressed() {
		if (lastPressed != null && lastPressedKeyCode == null) {
			lastPressed.mouseReleased(this,
			                          circuitBoard.getCurrentState(),
			                          lastMousePosition.getX() - lastPressed.getScreenX(),
			                          lastMousePosition.getY() - lastPressed.getScreenY());
		} else if (lastPressed != null) {
			lastPressed.keyReleased(this,
			                        circuitBoard.getCurrentState(),
			                        lastPressedKeyCode,
			                        lastPressedKeyCode.getName());
		}
		
		lastPressed = null;
		lastPressedKeyCode = null;
	}
	
	private void reset() {
		resetLastPressed();
		
		currentState = SelectingState.IDLE;
		
		setSelectedElements(Collections.emptySet());
		simulatorWindow.clearSelection();
		mayThrow(dummyCircuit::clearComponents);
		potentialComponent = null;
		isDraggedHorizontally = false;
		startConnection = null;
		endConnection = null;
		inspectLinkWires = null;
		
		simulatorWindow.setNeedsRepaint();
	}
	
	public void destroy() {
		circuitBoard.destroy();
	}
	
	public CircuitSim getSimulatorWindow() {
		return simulatorWindow;
	}
	
	/**
	 * @return the bounds for the circuit board (in the circuit coordinate system).
	 */
	public Bounds getCircuitBounds() {
		Set<GuiElement> elements = new HashSet<>();
		elements.addAll(this.getSelectedElements());
		elements.addAll(this.getCircuitBoard().getComponents());
		for (LinkWires links : this.getCircuitBoard().getLinks()) {
			elements.addAll(links.getWires());
		}

		int minX = elements.stream().mapToInt(el -> el.getX()).min().orElse(5) - 5;
		int minY = elements.stream().mapToInt(el -> el.getY()).min().orElse(5) - 5;
		int maxX = elements.stream().mapToInt(el -> el.getX() + el.getWidth()).max().orElse(0) + 5;
		int maxY = elements.stream().mapToInt(el -> el.getY() + el.getHeight()).max().orElse(0) + 5;

		return new BoundingBox(minX, minY, maxX - minX, maxY - minY);
	}

	public Circuit getCircuit() {
		return circuitBoard.getCircuit();
	}
	
	public CircuitBoard getCircuitBoard() {
		return circuitBoard;
	}
	
	Exception getCurrentError() {
		if (lastException != null && SHOW_ERROR_DURATION < System.currentTimeMillis() - lastExceptionTime) {
			lastException = null;
		}
		
		return lastException == null ? circuitBoard.getLastException() : lastException;
	}
	
	public Point2D getLastMousePosition() {
		return lastMousePosition;
	}
	
	public void setLastMousePosition(Point2D lastMousePosition) {
		this.lastMousePosition = lastMousePosition;
	}
	
	public Set<GuiElement> getSelectedElements() {
		return selectedElements;
	}
	
	public void setSelectedElements(Set<GuiElement> elements) {
		mayThrow(circuitBoard::finalizeMove);
		selectedElements.clear();
		selectedElements.addAll(elements);
		updateSelectedProperties();
	}
	
	private Properties getCommonSelectedProperties() {
		return selectedElements
			.stream()
			.filter(element -> element instanceof ComponentPeer<?>)
			.map(element -> ((ComponentPeer<?>)element).getProperties())
			.reduce(Properties::intersect)
			.orElse(new Properties());
	}
	
	private void updateSelectedProperties() {
		long componentCount = selectedElements.stream().filter(element -> element instanceof ComponentPeer<?>).count();
		if (componentCount == 1) {
			Optional<? extends ComponentPeer<?>>
				peer =
				selectedElements
					.stream()
					.filter(element -> element instanceof ComponentPeer<?>)
					.map(element -> ((ComponentPeer<?>)element))
					.findAny();
			peer.ifPresent(simulatorWindow::setProperties);
		} else if (componentCount > 1) {
			simulatorWindow.setProperties("Multiple selections", getCommonSelectedProperties());
		} else {
			simulatorWindow.clearProperties();
		}
	}
	
	public void modifiedSelection(ComponentCreator<?> componentCreator, Properties properties) {
		this.componentCreator = componentCreator;
		this.potentialComponentProperties = properties;
		
		mayThrow(circuitBoard::finalizeMove);
		mayThrow(dummyCircuit::clearComponents);
		
		if (currentState != SelectingState.IDLE && currentState != SelectingState.PLACING_COMPONENT &&
		    currentState != SelectingState.ELEMENT_SELECTED && currentState != SelectingState.ELEMENT_DRAGGED) {
			reset();
		}
		
		if (componentCreator != null) {
			setSelectedElements(Collections.emptySet());
			
			currentState = SelectingState.PLACING_COMPONENT;
			
			potentialComponent = componentCreator.createComponent(properties,
			                                                      GuiUtils.getCircuitCoord(lastMousePosition.getX()),
			                                                      GuiUtils.getCircuitCoord(lastMousePosition.getY()));
			mayThrow(() -> dummyCircuit.addComponent(potentialComponent.getComponent()));
			potentialComponent.setX(potentialComponent.getX() - potentialComponent.getWidth() / 2);
			potentialComponent.setY(potentialComponent.getY() - potentialComponent.getHeight() / 2);
			simulatorWindow.setProperties(potentialComponent);
			return;
		}
		
		if (properties != null && !properties.isEmpty() && !selectedElements.isEmpty()) {
			Map<ComponentPeer<?>, ComponentPeer<?>>
				newComponents =
				selectedElements
					.stream()
					.filter(element -> element instanceof ComponentPeer<?>)
					.map(element -> (ComponentPeer<?>)element)
					.collect(Collectors.toMap(component -> component,
					                          component -> (ComponentPeer<?>)ComponentManager
						                          .forClass(component.getClass())
						                          .createComponent(new Properties(component.getProperties()).mergeIfExists(
							                                           properties),
						                                           component.getX(),
						                                           component.getY())));
			
			simulatorWindow.getEditHistory().beginGroup();
			simulatorWindow
				.getSimulator()
				.runSync(() -> newComponents.forEach((oldComponent, newComponent) -> mayThrow(() -> circuitBoard.updateComponent(
					oldComponent,
					newComponent))));
			simulatorWindow.getEditHistory().endGroup();
			
			setSelectedElements(Stream
				                    .concat(selectedElements
					                            .stream()
					                            .filter(element -> !(element instanceof ComponentPeer<?>)),
				                            newComponents.values().stream())
				                    .collect(Collectors.toSet()));
			return;
		}
		
		currentState = SelectingState.IDLE;
		setSelectedElements(Collections.emptySet());
		potentialComponent = null;
		isDraggedHorizontally = false;
		startConnection = null;
		endConnection = null;
		simulatorWindow.setNeedsRepaint();
	}
	
	/**
	 * Converts a coordinate in the pixel coordinate system to the canvas coordinate system
	 * (by undoing the transforms applied.)
	 * 
	 * @param x Pixel X
	 * @param y Pixel Y
	 * @return the coordinate in the canvas coordinate system
	 */
	private Point2D pixelToCanvasCoord(double x, double y) {
		return new Point2D(x, y)
			.multiply(simulatorWindow.getScaleFactorInverted())
			.subtract(translateOrigin);
	}

	/**
	 * Sets `translateOrigin`, but restricts newOrigin to not display any out-of-bounds values
	 * @param newOrigin the new origin
	 */
	public void setTranslate(Point2D newOrigin) {
		double x = Math.min(0, newOrigin.getX());
		double y = Math.min(0, newOrigin.getY());
		translateOrigin = new Point2D(x, y);
	}

	public void paint(Canvas canvas) {
		if (canvas == null) return;
		GraphicsContext graphics = canvas.getGraphicsContext2D();
		graphics.save();
		try {
			graphics.setFont(GuiUtils.getFont(13));
			graphics.setFontSmoothingType(FontSmoothingType.LCD);
			
			// Set a background.
			boolean drawGrid = showGrid.getValue();
			graphics.setFill(drawGrid ? Color.DARKGRAY : Color.WHITE);
			graphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
			
			// Perform graphics transform.
			//
			// The transformation order here is reversed (like matrix multiplication), so:
			// Canvas -> Pixel: translate by (+tx, +ty), then scale by (scale)
			// Pixel -> Canvas: scale by (1/scale), then translate by (-tx, -ty)
			graphics.scale(simulatorWindow.getScaleFactor(), simulatorWindow.getScaleFactor());
			graphics.translate(translateOrigin.getX(), translateOrigin.getY());
			
			// Draw the light background and grid on the part of the canvas visible on the screen.
			if (drawGrid) {
				// Find the start of the tile at pixel (0, 0).
				Point2D canvasStart = pixelToCanvasCoord(0, 0);
				canvasStart = canvasStart.subtract(
					canvasStart.getX() % GuiUtils.BLOCK_SIZE,
					canvasStart.getY() % GuiUtils.BLOCK_SIZE
				);
				// Find the canvas coordinate for the end of the canvas.
				Point2D canvasEnd = pixelToCanvasCoord(canvas.getWidth(), canvas.getHeight());

				// BG of writable region
				graphics.setFill(Color.LIGHTGRAY);
				double bgOriginX = Math.max(0, canvasStart.getX());
				double bgOriginY = Math.max(0, canvasStart.getY());
				double bgWidth = canvasEnd.getX() - bgOriginX;
				double bgHeight = canvasEnd.getY() - bgOriginY;
				graphics.fillRect(bgOriginX, bgOriginY, bgWidth, bgHeight);
				// Grid
				graphics.setFill(Color.BLACK);
				for (double i = canvasStart.getX(); i < canvasEnd.getX(); i += GuiUtils.BLOCK_SIZE) {
					for (double j = canvasStart.getY(); j < canvasEnd.getY(); j += GuiUtils.BLOCK_SIZE) {
						graphics.fillRect(i, j, 1, 1);
					}
				}
			}
			
			try {
				circuitBoard.paint(graphics, inspectLinkWires);
			} catch (Exception exc) {
				getSimulatorWindow().getDebugUtil().logException(exc);
			}
			
			for (GuiElement selectedElement : selectedElements) {
				graphics.setStroke(Color.ORANGERED);
				if (selectedElement instanceof Wire) {
					double xOff = ((Wire)selectedElement).isHorizontal() ? 0 : 1;
					double yOff = ((Wire)selectedElement).isHorizontal() ? 1 : 0;
					
					graphics.strokeRect(selectedElement.getScreenX() - xOff,
					                    selectedElement.getScreenY() - yOff,
					                    selectedElement.getScreenWidth() + xOff * 2,
					                    selectedElement.getScreenHeight() + yOff * 2);
				} else {
					GuiUtils.drawShape(graphics::strokeRect, selectedElement);
				}
			}
			
			if (!simulatorWindow.isSimulationEnabled()) {
				graphics.save();
				try {
					graphics.setStroke(Color.RED);
					
					simulatorWindow.getSimulator().runSync(() -> {
						for (Pair<CircuitState, Link> linkToUpdate : simulatorWindow
							.getSimulator()
							.getLinksToUpdate()) {
							for (Port port : linkToUpdate.getValue().getParticipants()) {
								Optional<PortConnection>
									connection =
									circuitBoard
										.getComponents()
										.stream()
										.flatMap(c -> c.getConnections().stream())
										.filter(p -> p.getPort() == port)
										.findFirst();
								if (connection.isPresent()) {
									PortConnection portConn = connection.get();
									graphics.strokeOval(portConn.getScreenX() - 2, portConn.getScreenY() - 2, 10, 10);
								}
							}
						}
					});
				} finally {
					graphics.restore();
				}
			}
			
			if (inspectLinkWires != null && inspectLinkWires.getLink() != null && inspectLinkWires.isLinkValid()) {
				String value;
				try {
					value = circuitBoard.getCurrentState().getMergedValue(inspectLinkWires.getLink()).toString();
				} catch (Exception exc) {
					value = "Error";
				}
				
				Text text = new Text(value);
				text.setFont(graphics.getFont());
				Bounds bounds = text.getLayoutBounds();
				
				double x = lastMousePressed.getX() - bounds.getWidth() / 2 - 3;
				double y = lastMousePressed.getY() + 30;
				double width = bounds.getWidth() + 6;
				double height = bounds.getHeight() + 3;
				
				graphics.setLineWidth(1);
				graphics.setStroke(Color.BLACK);
				graphics.setFill(Color.ORANGE.brighter());
				graphics.fillRect(x, y, width, height);
				graphics.strokeRect(x, y, width, height);
				
				graphics.setFill(Color.BLACK);
				graphics.fillText(value, x + 3, y + height - 5);
			}
			
			switch (currentState) {
				case IDLE:
				case CONNECTION_SELECTED:
					if (startConnection != null) {
						graphics.save();
						try {
							graphics.setLineWidth(2);
							graphics.setStroke(Color.GREEN);
							graphics.strokeOval(startConnection.getScreenX() - 2,
							                    startConnection.getScreenY() - 2,
							                    10,
							                    10);
							
							if (endConnection != null) {
								graphics.strokeOval(endConnection.getScreenX() - 2,
								                    endConnection.getScreenY() - 2,
								                    10,
								                    10);
							}
							
							if (startConnection instanceof PortConnection) {
								PortConnection portConnection = (PortConnection)startConnection;
								String name = portConnection.getName();
								if (!name.isEmpty()) {
									Text text = new Text(name);
									text.setFont(graphics.getFont());
									Bounds bounds = text.getLayoutBounds();
									
									double x = startConnection.getScreenX() - bounds.getWidth() / 2 - 3;
									double y = startConnection.getScreenY() + 30;
									double width = bounds.getWidth() + 6;
									double height = bounds.getHeight() + 3;
									
									graphics.setLineWidth(1);
									graphics.setStroke(Color.BLACK);
									graphics.setFill(Color.ORANGE.brighter());
									graphics.fillRect(x, y, width, height);
									graphics.strokeRect(x, y, width, height);
									
									graphics.setFill(Color.BLACK);
									graphics.fillText(name, x + 3, y + height - 5);
								}
							}
						} finally {
							graphics.restore();
						}
					}
					break;
				case CONNECTION_DRAGGED: {
					graphics.save();
					try {
						graphics.setLineWidth(2);
						graphics.setStroke(Color.GREEN);
						graphics.strokeOval(startConnection.getScreenX() - 2, startConnection.getScreenY() - 2, 10,
						                    10);
						
						if (endConnection != null) {
							graphics.strokeOval(endConnection.getScreenX() - 2, endConnection.getScreenY() - 2, 10,
							                    10);
						}
						
						int startX = startConnection.getScreenX() + startConnection.getScreenWidth() / 2;
						int startY = startConnection.getScreenY() + startConnection.getScreenHeight() / 2;
						int pointX = GuiUtils.getScreenCircuitCoord(lastMousePosition.getX());
						int pointY = GuiUtils.getScreenCircuitCoord(lastMousePosition.getY());
						graphics.setStroke(isShiftDown ? Color.RED : Color.BLACK);
						if (isDraggedHorizontally) {
							graphics.strokeLine(startX, startY, pointX, startY);
							graphics.strokeLine(pointX, startY, pointX, pointY);
						} else {
							graphics.strokeLine(startX, startY, startX, pointY);
							graphics.strokeLine(startX, pointY, pointX, pointY);
						}
					} finally {
						graphics.restore();
					}
					break;
				}
				case PLACING_COMPONENT: {
					if (potentialComponent != null && isMouseInsideCanvas) {
						graphics.save();
						try {
							potentialComponent.paint(graphics, dummyCircuit.getTopLevelState());
						} finally {
							graphics.restore();
						}
						
						for (Connection connection : potentialComponent.getConnections()) {
							graphics.save();
							try {
								connection.paint(graphics, dummyCircuit.getTopLevelState());
							} finally {
								graphics.restore();
							}
						}
					}
					break;
				}
				case BACKGROUND_DRAGGED: {
					if (!simulatorWindow.isClickMode()) {
						double startX = Math.min(lastMousePressed.getX(), lastMousePosition.getX());
						double startY = Math.min(lastMousePressed.getY(), lastMousePosition.getY());
						double width = Math.abs(lastMousePosition.getX() - lastMousePressed.getX());
						double height = Math.abs(lastMousePosition.getY() - lastMousePressed.getY());
						
						graphics.setStroke(Color.GREEN.darker());
						graphics.strokeRect(startX, startY, width, height);
					}
					break;
				}
				default:
					break;
			}
		} finally {
			graphics.restore();
		}
	}
	
	@FunctionalInterface
	interface ThrowableRunnable {
		void run() throws Exception;
	}
	
	boolean mayThrow(ThrowableRunnable runnable) {
		try {
			runnable.run();
			
			if (lastException != null && SHOW_ERROR_DURATION < System.currentTimeMillis() - lastExceptionTime) {
				lastException = null;
			}
			
			return false;
		} catch (SimulationException exc) {
			lastException = exc;
			lastExceptionTime = System.currentTimeMillis();
			return true;
		} catch (Exception exc) {
			getSimulatorWindow().getDebugUtil().logException(exc);
			lastException = exc;
			lastExceptionTime = System.currentTimeMillis();
			return true;
		}
	}
	
	private void handleArrowPressed(Direction direction) {
		if (currentState == SelectingState.PLACING_COMPONENT || !getSelectedElements().isEmpty()) {
			Properties
				props =
				new Properties(currentState == SelectingState.PLACING_COMPONENT ?
				               this.potentialComponentProperties :
				               getCommonSelectedProperties());
			props.setValue(Properties.DIRECTION, direction);
			modifiedSelection(componentCreator, props);
		}
	}
	
	public void keyPressed(KeyEvent e) {
		if (e.getCode() != KeyCode.SHIFT && lastPressed == null && selectedElements.size() == 1) {
			lastPressed = selectedElements.iterator().next();
			lastPressedConsumed =
				lastPressed.keyPressed(this, circuitBoard.getCurrentState(), e.getCode(), e.getText());
			lastPressedKeyCode = e.getCode();
			simulatorWindow.setNeedsRepaint();
		}
		
		if (lastPressed != null && lastPressedConsumed) {
			return;
		}
		
		simulatorWindow.setNeedsRepaint();
		
		if (e.isShortcutDown()) {
			isCtrlDown = true;
		}

		switch (e.getCode()) {
			case RIGHT: {
				if (currentState != SelectingState.ELEMENT_DRAGGED) {
					e.consume();
					handleArrowPressed(Direction.EAST);
				}
				break;
			}
			case LEFT: {
				if (currentState != SelectingState.ELEMENT_DRAGGED) {
					e.consume();
					handleArrowPressed(Direction.WEST);
				}
				break;
			}
			case UP: {
				if (currentState != SelectingState.ELEMENT_DRAGGED) {
					e.consume();
					handleArrowPressed(Direction.NORTH);
				}
				break;
			}
			case DOWN: {
				if (currentState != SelectingState.ELEMENT_DRAGGED) {
					e.consume();
					handleArrowPressed(Direction.SOUTH);
				}
				break;
			}
			case SHIFT:
				if (currentState != SelectingState.CONNECTION_SELECTED &&
				    currentState != SelectingState.CONNECTION_DRAGGED) {
					simulatorWindow.setClickMode(true);
				}
				isShiftDown = true;
				break;
			case DELETE:
			case BACK_SPACE:
				if (!getSelectedElements().isEmpty()) {
					mayThrow(circuitBoard::finalizeMove);
					mayThrow(() -> circuitBoard.removeElements(selectedElements));
					reset();
				}
				break;
			case ESCAPE:
				if (currentState == SelectingState.ELEMENT_DRAGGED) {
					mayThrow(() -> circuitBoard.moveElements(0, 0, false));
				}
				
				reset();
				break;
			default:
				break;
		}
	}
	
	public void keyTyped(KeyEvent e) {
		if (selectedElements.size() == 1 && !e.isShortcutDown()) {
			GuiElement element = selectedElements.iterator().next();
			element.keyTyped(this, circuitBoard.getCurrentState(), e.getCharacter());
			simulatorWindow.setNeedsRepaint();
		}
	}
	
	public void keyReleased(KeyEvent e) {
		if (e.getCode().isModifierKey()) {
			if (!e.isShortcutDown()) {
				isCtrlDown = false;
				simulatorWindow.setNeedsRepaint();
			}
			if (!e.isShiftDown()) {
				simulatorWindow.setClickMode(false);
					isShiftDown = false;
					simulatorWindow.setNeedsRepaint();
			}
		}
		
		if (lastPressed != null && lastPressedKeyCode == e.getCode()) {
			lastPressed.keyReleased(this, circuitBoard.getCurrentState(), e.getCode(), e.getText());
			lastPressed = null;
			lastPressedKeyCode = null;
			simulatorWindow.setNeedsRepaint();
		}
	}
	
	private void addCurrentWire() {
		int endMidX = endConnection == null ? GuiUtils.getCircuitCoord(lastMousePosition.getX()) :
		              endConnection.getX();
		int endMidY = endConnection == null ? GuiUtils.getCircuitCoord(lastMousePosition.getY()) :
		              endConnection.getY();
		
		Set<Wire> wires = new HashSet<>();
		
		if (endMidX - startConnection.getX() != 0 && endMidY - startConnection.getY() != 0) {
			simulatorWindow.getEditHistory().beginGroup();
			if (isDraggedHorizontally) {
				wires.add(new Wire(null,
				                   startConnection.getX(),
				                   startConnection.getY(),
				                   endMidX - startConnection.getX(),
				                   true));
				wires.add(new Wire(null, endMidX, startConnection.getY(), endMidY - startConnection.getY(), false));
			} else {
				wires.add(new Wire(null,
				                   startConnection.getX(),
				                   startConnection.getY(),
				                   endMidY - startConnection.getY(),
				                   false));
				wires.add(new Wire(null, startConnection.getX(), endMidY, endMidX - startConnection.getX(), true));
			}
			simulatorWindow.getEditHistory().endGroup();
		} else if (endMidX - startConnection.getX() != 0) {
			wires.add(new Wire(null,
			                   startConnection.getX(),
			                   startConnection.getY(),
			                   endMidX - startConnection.getX(),
			                   true));
		} else if (endMidY - startConnection.getY() != 0) {
			wires.add(new Wire(null, endMidX, startConnection.getY(), endMidY - startConnection.getY(), false));
		} else {
			Set<Connection> connections = circuitBoard.getConnections(startConnection.getX(), startConnection.getY());
			
			setSelectedElements(Stream
				                    .concat(isCtrlDown ? getSelectedElements().stream() : Stream.empty(),
				                            connections.stream().map(Connection::getParent))
				                    .collect(Collectors.toSet()));
		}
		
		if (isShiftDown) {
			mayThrow(() -> circuitBoard.removeElements(wires));
		} else {
			simulatorWindow.getEditHistory().beginGroup();
			for (Wire w : wires) {
				mayThrow(() -> circuitBoard.addWire(w.getX(), w.getY(), w.getLength(), w.isHorizontal()));
			}
			simulatorWindow.getEditHistory().endGroup();
		}
	}
	
	private void checkStartConnection() {
		if (currentState != SelectingState.CONNECTION_DRAGGED) {
			Set<Connection>
				selectedConns =
				circuitBoard.getConnections(GuiUtils.getCircuitCoord(lastMousePosition.getX()),
				                            GuiUtils.getCircuitCoord(lastMousePosition.getY()));
			
			Connection selected = null;
			
			for (Connection connection : selectedConns) {
				if (connection instanceof PortConnection) {
					selected = connection;
					break;
				}
			}
			
			if (selected == null && !selectedConns.isEmpty()) {
				selected = selectedConns.iterator().next();
			}
			
			if (startConnection != selected) {
				simulatorWindow.setNeedsRepaint();
			}
			
			startConnection = selected;
		}
	}
	
	private void checkEndConnection(Point2D prevMousePosition) {
		if (currentState == SelectingState.CONNECTION_DRAGGED) {
			int currDiffX = GuiUtils.getCircuitCoord(lastMousePosition.getX()) - startConnection.getX();
			int prevDiffX = GuiUtils.getCircuitCoord(prevMousePosition.getX()) - startConnection.getX();
			int currDiffY = GuiUtils.getCircuitCoord(lastMousePosition.getY()) - startConnection.getY();
			int prevDiffY = GuiUtils.getCircuitCoord(prevMousePosition.getY()) - startConnection.getY();
			
			if (currDiffX == 0 || prevDiffX == 0 ||
			    currDiffX / Math.abs(currDiffX) != prevDiffX / Math.abs(prevDiffX)) {
				if (isDraggedHorizontally) {
					simulatorWindow.setNeedsRepaint();
				}
				
				isDraggedHorizontally = false;
			}
			
			if (currDiffY == 0 || prevDiffY == 0 ||
			    currDiffY / Math.abs(currDiffY) != prevDiffY / Math.abs(prevDiffY)) {
				if (!isDraggedHorizontally) {
					simulatorWindow.setNeedsRepaint();
				}
				
				isDraggedHorizontally = true;
			}
			
			Connection
				connection =
				circuitBoard.findConnection(GuiUtils.getCircuitCoord(lastMousePosition.getX()),
				                            GuiUtils.getCircuitCoord(lastMousePosition.getY()));
			
			if (endConnection != connection) {
				simulatorWindow.setNeedsRepaint();
			}
			
			endConnection = connection;
		}
	}
	
	private void updatePotentialComponent() {
		if (potentialComponent != null) {
			potentialComponent.setX(
				GuiUtils.getCircuitCoord(lastMousePosition.getX()) - potentialComponent.getWidth() / 2);
			potentialComponent.setY(
				GuiUtils.getCircuitCoord(lastMousePosition.getY()) - potentialComponent.getHeight() / 2);
			
			simulatorWindow.setNeedsRepaint();
		}
	}

	public void mousePressed(MouseEvent e) {
		this.usingTrackpad = false;
		if (e.getButton() != MouseButton.PRIMARY) {
			switch (currentState) {
				case PLACING_COMPONENT, CONNECTION_SELECTED, CONNECTION_DRAGGED -> reset();
				default -> {}
			}
			
			return;
		}
		
		lastMousePosition = pixelToCanvasCoord(e.getX(), e.getY());
		lastMousePressed = pixelToCanvasCoord(e.getX(), e.getY());
		lastMousePressedPan = new Point2D(e.getX(), e.getY());
		translateOriginBeforePan = translateOrigin;

		switch (currentState) {
			case ELEMENT_DRAGGED:
			case CONNECTION_SELECTED:
			case BACKGROUND_DRAGGED:
				break;
			
			case IDLE:
			case ELEMENT_SELECTED:
				inspectLinkWires = null;
				
				if (startConnection != null) {
					if (simulatorWindow.isClickMode()) {
						inspectLinkWires = startConnection.getLinkWires();
						currentState = SelectingState.IDLE;
					} else {
						if (isCtrlDown && getSelectedElements().isEmpty()) {
							currentState = SelectingState.CONNECTION_DRAGGED;
						} else {
							currentState = SelectingState.CONNECTION_SELECTED;
						}
					}
				} else {
					Optional<GuiElement>
						clickedComponent =
						Stream
							.concat(getSelectedElements().stream(), circuitBoard.getComponents().stream())
							.filter(peer -> peer.containsScreenCoord((int)lastMousePressed.getX(),
							                                         (int)lastMousePressed.getY()))
							.findFirst();
					if (clickedComponent.isPresent()) {
						GuiElement selectedElement = clickedComponent.get();
						
						if (e.getClickCount() == 2 && selectedElement instanceof SubcircuitPeer) {
							reset();
							((SubcircuitPeer)selectedElement).switchToSubcircuit(this);
						} else if (simulatorWindow.isClickMode() && lastPressed == null) {
							lastPressed = selectedElement;
							selectedElement.mousePressed(this,
							                             circuitBoard.getCurrentState(),
							                             lastMousePosition.getX() - selectedElement.getScreenX(),
							                             lastMousePosition.getY() - selectedElement.getScreenY());
						} else if (isCtrlDown) {
							Set<GuiElement> elements = new HashSet<>(getSelectedElements());

							// toggle selected element
							if (elements.contains(selectedElement)) {
								elements.remove(selectedElement);
							} else {
								elements.add(selectedElement);
							}

							setSelectedElements(elements);
						} else if (!getSelectedElements().contains(selectedElement)) {
							setSelectedElements(Collections.singleton(selectedElement));
						}
						
						if (currentState == SelectingState.IDLE) {
							currentState = SelectingState.ELEMENT_SELECTED;
						}
					} else if (!isCtrlDown) {
						reset();
					}
				}
				break;
			
			case CONNECTION_DRAGGED:
				addCurrentWire();
				if (isCtrlDown) {
					Set<Connection>
						selectedConns =
						circuitBoard.getConnections(GuiUtils.getCircuitCoord(lastMousePressed.getX()),
						                            GuiUtils.getCircuitCoord(lastMousePressed.getY()));
					if (!selectedConns.isEmpty()) {
						startConnection = selectedConns.iterator().next();
					}
				} else {
					currentState = SelectingState.IDLE;
					startConnection = null;
					endConnection = null;
				}
				break;
			
			case PLACING_COMPONENT:
				ComponentPeer<?>
					newComponent =
					componentCreator.createComponent(potentialComponentProperties,
					                                 potentialComponent.getX(),
					                                 potentialComponent.getY());
				
				mayThrow(() -> circuitBoard.addComponent(newComponent));
				
				if (!isCtrlDown) {
					reset();
					if (circuitBoard.getComponents().contains(newComponent)) {
						setSelectedElements(Collections.singleton(newComponent));
					}
					
					currentState = SelectingState.ELEMENT_SELECTED;
				}
				break;
		}
		
		simulatorWindow.setNeedsRepaint();
	}
	
	public void mouseReleased(MouseEvent e) {
		if (e.getButton() != MouseButton.PRIMARY) {
			return;
		}
		
		lastMousePosition = pixelToCanvasCoord(e.getX(), e.getY());
		
		switch (currentState) {
			case IDLE:
			case ELEMENT_SELECTED:
			case ELEMENT_DRAGGED:
				resetLastPressed();
				mayThrow(() -> {
					Set<GuiElement> newElements = circuitBoard.finalizeMove();
					if (newElements != null) {
						setSelectedElements(newElements);
					}
				});
				currentState = SelectingState.IDLE;
				break;
			
			case CONNECTION_SELECTED: {
				Set<GuiElement> selectedEls = new HashSet<>(isCtrlDown ? getSelectedElements() : Set.of());

				for (Connection c : circuitBoard.getConnections(startConnection.getX(), startConnection.getY())) {
					GuiElement el = c.getParent();
					// toggle wire
					if (selectedEls.contains(el)) {
						selectedEls.remove(el);
					} else {
						selectedEls.add(el);
					}
				}
				
				setSelectedElements(selectedEls);
				currentState = SelectingState.IDLE;
				break;
			}
			
			case CONNECTION_DRAGGED: {
				if (!getSelectedElements().isEmpty() || !isCtrlDown) {
					addCurrentWire();
					currentState = SelectingState.IDLE;
					startConnection = null;
					endConnection = null;
				}
				break;
			}
			
			case PLACING_COMPONENT:
				if (isCtrlDown) {
					break;
				}
			case BACKGROUND_DRAGGED:
				if (simulatorWindow.isClickMode()) {
					setTranslate(
						translateOriginBeforePan
							.add(e.getX(), e.getY())
							.subtract(lastMousePressedPan)
					);
				}
				currentState = SelectingState.IDLE;
				break;
		}
		
		checkStartConnection();
		
		simulatorWindow.setNeedsRepaint();
	}
	
	public void mouseDragged(MouseEvent e) {
		if (e.getButton() != MouseButton.PRIMARY) {
			return;
		}
		
		Point2D prevMousePosition = lastMousePosition;
		lastMousePosition = pixelToCanvasCoord(e.getX(), e.getY());
		
		switch (currentState) {
			case IDLE:
			case BACKGROUND_DRAGGED:
				currentState = SelectingState.BACKGROUND_DRAGGED;
				
				int startX = (int)(Math.min(lastMousePressed.getX(), lastMousePosition.getX()));
				int startY = (int)(Math.min(lastMousePressed.getY(), lastMousePosition.getY()));
				int width = (int)Math.abs(lastMousePosition.getX() - lastMousePressed.getX());
				int height = (int)Math.abs(lastMousePosition.getY() - lastMousePressed.getY());
				
				if (!isCtrlDown) {
					selectedElements.clear();
				}
				
				if (simulatorWindow.isClickMode()) {
					setTranslate(
						translateOriginBeforePan
							.add(e.getX(), e.getY())
							.subtract(lastMousePressedPan)
					);
				} else {
					setSelectedElements(Stream
											.concat(getSelectedElements().stream(), Stream
												.concat(circuitBoard.getComponents().stream(),
														circuitBoard
															.getLinks()
															.stream()
															.flatMap(link -> link.getWires().stream()))
												.filter(peer -> peer.isWithinScreenCoord(startX, startY, width, height)))
											.collect(Collectors.toSet()));
				}
				break;
			
			case ELEMENT_SELECTED:
				if (simulatorWindow.isClickMode()) {
					break;
				}
			
			case PLACING_COMPONENT:
				if (isCtrlDown) {
					updatePotentialComponent();
					break;
				}
			case ELEMENT_DRAGGED:
				int dx = GuiUtils.getCircuitCoord(lastMousePosition.getX() - lastMousePressed.getX());
				int dy = GuiUtils.getCircuitCoord(lastMousePosition.getY() - lastMousePressed.getY());
				
				if (dx != 0 || dy != 0 || currentState == SelectingState.ELEMENT_DRAGGED) {
					currentState = SelectingState.ELEMENT_DRAGGED;
					
					if (!circuitBoard.isMoving()) {
						mayThrow(() -> circuitBoard.initMove(getSelectedElements()));
					}
					
					mayThrow(() -> circuitBoard.moveElements(dx, dy, !isCtrlDown));
				}
				break;
			
			case CONNECTION_SELECTED:
			case CONNECTION_DRAGGED:
				currentState = SelectingState.CONNECTION_DRAGGED;
				checkEndConnection(prevMousePosition);
				break;
		}
		
		checkStartConnection();
		
		simulatorWindow.setNeedsRepaint();
	}
	
	private GuiElement lastEntered;
	
	public void mouseMoved(MouseEvent e) {
		Point2D prevMousePosition = lastMousePosition;
		lastMousePosition = pixelToCanvasCoord(e.getX(), e.getY());
		
		if (currentState != SelectingState.IDLE) {
			simulatorWindow.setNeedsRepaint();
		}
		
		updatePotentialComponent();
		
		checkStartConnection();
		checkEndConnection(prevMousePosition);
		
		if (startConnection == null &&
		    (currentState == SelectingState.IDLE || currentState == SelectingState.ELEMENT_SELECTED)) {
			Optional<ComponentPeer<?>>
				component =
				circuitBoard
					.getComponents()
					.stream()
					.filter(c -> c.containsScreenCoord((int)lastMousePosition.getX(), (int)lastMousePosition.getY()))
					.findFirst();
			if (component.isPresent()) {
				ComponentPeer<?> peer = component.get();
				if (peer != lastEntered) {
					if (lastEntered != null) {
						lastEntered.mouseExited(this, circuitBoard.getCurrentState());
					}
					
					(lastEntered = peer).mouseEntered(this, circuitBoard.getCurrentState());
					
					simulatorWindow.setNeedsRepaint();
				}
			} else if (lastEntered != null) {
				lastEntered.mouseExited(this, circuitBoard.getCurrentState());
				lastEntered = null;
				
				simulatorWindow.setNeedsRepaint();
			}
		} else if (lastEntered != null) {
			lastEntered.mouseExited(this, circuitBoard.getCurrentState());
			lastEntered = null;
			
			simulatorWindow.setNeedsRepaint();
		}
	}
	
	public void mouseEntered(MouseEvent e) {
		isMouseInsideCanvas = true;
		simulatorWindow.setNeedsRepaint();
	}
	
	public void mouseExited(MouseEvent e) {
		isMouseInsideCanvas = false;
		simulatorWindow.setNeedsRepaint();
	}
	
	/**
	 * Applies a zoom, setting the new scale to newScale and zooming around the origin.
	 * @param originX the origin's X coordinate
	 * @param originY the origin's Y coordinate
	 * @param zoomFactor the factor to scale by
	 *     This is a multiplier on the old scale. If the old scale is 0.5x and zoomFactor is 2,
	 *     this sets the scale to 1.0x.
	 */
	private void applyZoom(double originX, double originY, double zoomFactor) {
		// Zoom
		double oldScale = simulatorWindow.getScaleFactor();
		zoomFactor = CircuitSim.clampScaleFactor(oldScale * zoomFactor);
		simulatorWindow.setScaleFactor(zoomFactor);
		
		// Zoom in on point.
		// Let (tx, ty) = translateOrigin.
		// Pixel (x, y) must be at the same canvas coordinate before and after zooming, 
		// so we must meet the constraint (x / oldScale - tx = x / newScale - tx').
		// When you solve this constraint, you get: (tx' = tx - factor * x),
		// where factor is the value below.
		double factor = (zoomFactor - oldScale) / (zoomFactor * oldScale);
		setTranslate(translateOrigin.subtract(factor * originX, factor * originY));
	}

	public void scrollStarted(ScrollEvent e) {
		// If user presses control during scroll, it should not change operation.
		this.zoomOnScroll = this.isCtrlDown;
		this.usingTrackpad = true;
	}
	public void scroll(ScrollEvent e) {
		boolean useZoom = this.usingTrackpad ? this.zoomOnScroll : this.isCtrlDown;
		if (useZoom) {
			// Zoom
			// We don't need inertia for zoom
			if (!e.isInertia()) {
				applyZoom(e.getX(), e.getY(), Math.pow(2, e.getDeltaY() / 32));
			}
		} else {
			// Pan
			Point2D delta = new Point2D(e.getDeltaX(), e.getDeltaY())
				.multiply(simulatorWindow.getScaleFactorInverted());
			setTranslate(translateOrigin.add(delta));
		}
		simulatorWindow.setNeedsRepaint();
	}
	public void scrollFinished(ScrollEvent e) {
	}
	
	public void zoom(ZoomEvent e) {
		applyZoom(e.getX(), e.getY(), e.getZoomFactor());
		simulatorWindow.setNeedsRepaint();
	}
	
	public void focusGained() {
	}
	
	public void focusLost() {
		isCtrlDown = false;
		isShiftDown = false;
		mouseExited(null);
		simulatorWindow.setClickMode(false);
		resetLastPressed();
		simulatorWindow.setNeedsRepaint();
	}

	public void contextMenuRequested(ContextMenuEvent e) {
		ContextMenu menu = new ContextMenu();
			
		MenuItem copy = new MenuItem("Copy");
		copy.setOnAction(event1 -> simulatorWindow.copySelectedComponents());
		
		MenuItem cut = new MenuItem("Cut");
		cut.setOnAction(event1 -> simulatorWindow.cutSelectedComponents());
		
		MenuItem paste = new MenuItem("Paste");
		paste.setOnAction(event1 -> simulatorWindow.pasteFromClipboard());
		
		MenuItem delete = new MenuItem("Delete");
		delete.setOnAction(event1 -> {
			mayThrow(() -> circuitBoard.removeElements(selectedElements));
			setSelectedElements(Collections.emptySet());
			reset();
		});
		
		Optional<ComponentPeer<?>>
			any =
			circuitBoard
				.getComponents()
				.stream()
				.filter(component -> component.containsScreenCoord((int)Math.round(
																	   e.getX() * simulatorWindow.getScaleFactorInverted()),
																   (int)Math.round(e.getY() *
																				   simulatorWindow.getScaleFactorInverted())))
				.findAny();
		
		if (any.isPresent()) {
			if (isCtrlDown) {
				Set<GuiElement> selected = new HashSet<>(getSelectedElements());
				selected.add(any.get());
				setSelectedElements(selected);
			} else if (!getSelectedElements().contains(any.get())) {
				setSelectedElements(Collections.singleton(any.get()));
			}
		}
		
		if (getSelectedElements().size() > 0) {
			menu.getItems().addAll(copy, cut, paste, delete);
		} else {
			menu.getItems().add(paste);
		}
		
		if (getSelectedElements().size() == 1) {
			menu.getItems().addAll(getSelectedElements().iterator().next().getContextMenuItems(this));
		}
		
		if (menu.getItems().size() > 0) {
			if (e.getTarget() instanceof Node) {
				Node target = (Node) e.getTarget();
				menu.show(target.getScene().getWindow(), e.getScreenX(), e.getScreenY());
			}
		}
	}
}
