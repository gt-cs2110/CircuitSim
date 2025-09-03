package com.ra4king.circuitsim.gui.peers.wiring;

import java.util.ArrayList;
import java.util.List;

import com.ra4king.circuitsim.gui.ComponentManager.ComponentManagerInterface;
import com.ra4king.circuitsim.gui.CircuitSimVersion;
import com.ra4king.circuitsim.gui.ComponentPeer;
import com.ra4king.circuitsim.gui.Connection.PortConnection;
import com.ra4king.circuitsim.gui.GuiUtils;
import com.ra4king.circuitsim.gui.properties.IntegerString;
import com.ra4king.circuitsim.gui.Properties;
import com.ra4king.circuitsim.gui.Properties.Base;
import com.ra4king.circuitsim.gui.Properties.Property;
import com.ra4king.circuitsim.simulator.CircuitState;
import com.ra4king.circuitsim.simulator.WireValue;
import com.ra4king.circuitsim.simulator.components.wiring.Constant;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.util.Pair;

/**
 * @author Roi Atalla
 */
public class ConstantPeer extends ComponentPeer<Constant> {
	/**
	 * In 1.9.2b and prior, unprefixed constants resolve to base 10.
	 * In 1.10.0 [2110 edition] and after, unprefixed constants resolve to the display base.
	 */
	private static final CircuitSimVersion MAXIMUM_VERSION_FOR_LEGACY_VALUE_PARSING = new CircuitSimVersion("1.9.2b");

	public static void installComponent(ComponentManagerInterface manager) {
		manager.addComponent(
			new Pair<>("Wiring", "Constant"),
			new Image(ConstantPeer.class.getResourceAsStream("/images/Constant.png")),
			new Properties());
	}
	
	private final WireValue value;
	
	public ConstantPeer(Properties props, int x, int y) {
		super(x, y, 0, 0);
		
		Properties properties = new Properties();
		properties.ensureProperty(Properties.LABEL);
		properties.ensureProperty(Properties.LABEL_LOCATION);
		properties.ensureProperty(Properties.DIRECTION);
		properties.ensureProperty(Properties.BITSIZE);
		properties.ensureProperty(Properties.BASE);
		
		// VALUE property:
		// This property depends on BASE. So we have to merge BASE first so the value is updated,
		// before we can create VALUE (we actually merge every property except VALUE, but that's fine).
		//
		// Then, we create VALUE. Finally, we update it with the old value (if one exists).
		// This is also special for VALUE, because for IntegerString values, 
		// it should update to reflect the new base.
		// So, if the old value was an IntegerString, we override the default.
		Property<?> oldValueProperty = props.getProperty(Properties.VALUE.name);
		properties.clearProperty(Properties.VALUE.name);
		properties.mergeIfExists(props);

		Base base = properties.getValue(Properties.BASE);
		Property<IntegerString> valueProperty = Properties.VALUE(base.value);
		properties.ensureProperty(valueProperty);

		// Handling previous input:
		if (oldValueProperty != null) {
			if (oldValueProperty.value instanceof IntegerString) {
				IntegerString valStr = (IntegerString) oldValueProperty.value;
				if (valStr.getBase() != base.value) {
					// Replace with value in new base:
					String newText = valStr.toString(base.value);
					properties.parseAndSetValue(valueProperty, newText);
				} else {
					// If base is the same, repropagate the old value (but not the validator)
					properties.setValue(valueProperty, valStr);
				}
			} else if (props.getVersion().compareTo(MAXIMUM_VERSION_FOR_LEGACY_VALUE_PARSING) <= 0) {
				// On importing a legacy file:
				// Convert all legacy constants (base 10) by converting string to display base:
				IntegerString valStr = IntegerString.parseFromLegacy(oldValueProperty.getStringValue(), base.value);
				properties.setValue(valueProperty, valStr);
			} else {
				// On importing a regular file:
				properties.updateIfExists(oldValueProperty);
			}
		}
		//

		Constant constant = new Constant(
			properties.getValue(Properties.LABEL),
			properties.getValue(Properties.BITSIZE),
			properties.getValue(valueProperty).getValue());
		
		int bitSize = constant.getBitSize();
		switch (base) {
			case BINARY -> {
				setWidth(Math.max(2, Math.min(8, bitSize)));
				setHeight((int)Math.round((1 + (bitSize - 1) / 8) * 1.5));
			}
			case HEXADECIMAL -> {
				setWidth(Math.max(2, 1 + (bitSize - 1) / 4));
				setHeight(2);
			}
			case DECIMAL -> {
				// 3.322 ~ log_2(10)
				int width = Math.max(2, (int)Math.ceil(bitSize / 3.322));
				width += bitSize == 32 ? 1 : 0;
				setWidth(width);
				setHeight(2);
			}
		}
		
		value = WireValue.of(constant.getValue(), bitSize);
		
		List<PortConnection> connections = new ArrayList<>();
		switch (properties.getValue(Properties.DIRECTION)) {
			case EAST -> connections.add(new PortConnection(this, constant.getPort(0), getWidth(), getHeight() / 2));
			case WEST -> connections.add(new PortConnection(this, constant.getPort(0), 0, getHeight() / 2));
			case NORTH -> connections.add(new PortConnection(this, constant.getPort(0), getWidth() / 2, 0));
			case SOUTH -> connections.add(new PortConnection(this, constant.getPort(0), getWidth() / 2, getHeight()));
		}
		
		init(constant, properties, connections);
	}
	
	@Override
	public void paint(GraphicsContext graphics, CircuitState circuitState) {
		GuiUtils.drawName(graphics, this, getProperties().getValue(Properties.LABEL_LOCATION));
		
		graphics.setFont(GuiUtils.getFont(16));
		graphics.setFill(Color.GRAY);
		graphics.setStroke(Color.GRAY);
		
		graphics.fillRoundRect(getScreenX(), getScreenY(), getScreenWidth(), getScreenHeight(), 10, 10);
		graphics.strokeRoundRect(getScreenX(), getScreenY(), getScreenWidth(), getScreenHeight(), 10, 10);
		
		String valStr = switch (getProperties().getValue(Properties.BASE)) {
			case BINARY -> value.toString();
			case HEXADECIMAL -> value.toHexString();
			case DECIMAL -> value.toDecString();
		};
		
		if (value.getBitSize() > 1) {
			graphics.setFill(Color.BLACK);
		} else {
			GuiUtils.setBitColor(graphics, value.getBit(0));
		}
		
		if (getProperties().getValue(Properties.BASE) == Properties.Base.DECIMAL) {
			GuiUtils.drawValueOneLine(graphics, valStr, getScreenX(), getScreenY(), getScreenWidth());
		} else {
			GuiUtils.drawValue(graphics, valStr, getScreenX(), getScreenY(), getScreenWidth());
		}
	}
}
