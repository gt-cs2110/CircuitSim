package com.ra4king.circuitsimulator.gui.peers.arithmetic;

import java.util.ArrayList;
import java.util.List;

import com.ra4king.circuitsimulator.gui.ComponentManager.ComponentManagerInterface;
import com.ra4king.circuitsimulator.gui.ComponentPeer;
import com.ra4king.circuitsimulator.gui.Connection.PortConnection;
import com.ra4king.circuitsimulator.gui.GuiUtils;
import com.ra4king.circuitsimulator.gui.Properties;
import com.ra4king.circuitsimulator.simulator.CircuitState;
import com.ra4king.circuitsimulator.simulator.components.arithmetic.Negator;

import javafx.geometry.Bounds;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.util.Pair;

/**
 * @author Roi Atalla
 */
public class NegatorPeer extends ComponentPeer<Negator> {
	public static void installComponent(ComponentManagerInterface manager) {
		manager.addComponent(new Pair<>("Arithmetic", "Negator"),
		                     new Image(NegatorPeer.class.getResourceAsStream("/resources/Negator.png")),
		                     new Properties());
	}
	
	public NegatorPeer(Properties props, int x, int y) {
		super(x, y, 4, 4);
		
		Properties properties = new Properties();
		properties.ensureProperty(Properties.LABEL);
		properties.ensureProperty(Properties.LABEL_LOCATION);
		properties.ensureProperty(Properties.BITSIZE);
		properties.mergeIfExists(props);
		
		Negator negator = new Negator(properties.getValue(Properties.LABEL),
		                              properties.getValue(Properties.BITSIZE));
		
		List<PortConnection> connections = new ArrayList<>();
		connections.add(new PortConnection(this, negator.getPort(Negator.IN_PORT), "In", 0, 2));
		connections.add(new PortConnection(this, negator.getPort(Negator.OUT_PORT), "Out", 4, 2));
		
		init(negator, properties, connections);
	}
	
	@Override
	public void paint(GraphicsContext graphics, CircuitState circuitState) {
		GuiUtils.drawName(graphics, this, getProperties().getValue(Properties.LABEL_LOCATION));
		
		graphics.setFill(Color.WHITE);
		graphics.setStroke(Color.BLACK);
		GuiUtils.drawShape(graphics::fillRect, this);
		GuiUtils.drawShape(graphics::strokeRect, this);
		
		graphics.setFont(GuiUtils.getFont(16, true));
		Bounds bounds = GuiUtils.getBounds(graphics.getFont(), "-x");
		graphics.setFill(Color.BLACK);
		graphics.fillText("-x",
		                  getScreenX() + (getScreenWidth() - bounds.getWidth()) * 0.5,
		                  getScreenY() + (getScreenHeight() + bounds.getHeight()) * 0.45);
	}
}