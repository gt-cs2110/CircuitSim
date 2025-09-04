package com.ra4king.circuitsim.gui.properties;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ra4king.circuitsim.gui.Properties.PropertyValidator;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * @author Roi Atalla
 */
public class PropertyListValidator<T> implements PropertyValidator<T> {
	private final List<T> validValues;
	private final Function<T, String> toString;
	
	public PropertyListValidator(T[] validValues) {
		this(Arrays.asList(validValues));
	}
	
	public PropertyListValidator(List<T> validValues) {
		this(validValues, T::toString);
	}
	
	public PropertyListValidator(T[] validValues, Function<T, String> toString) {
		this(Arrays.asList(validValues), toString);
	}
	
	public PropertyListValidator(List<T> validValues, Function<T, String> toString) {
		this.validValues = Collections.unmodifiableList(validValues);
		this.toString = toString;
	}
	
	public List<T> getValidValues() {
		return validValues;
	}
	
	@Override
	public int hashCode() {
		List<String> values = validValues.stream().map(toString).collect(Collectors.toList());
		return validValues.hashCode() ^ values.hashCode();
	}
	
	@Override
	public boolean equals(Object other) {
		if (other instanceof PropertyListValidator) {
			PropertyListValidator<?> validator = (PropertyListValidator<?>)other;
			return this.validValues.equals(validator.validValues);
		}
		
		return true;
	}
	
	@Override
	public T parse(String value) {
		for (T t : validValues) {
			if (toString.apply(t).equals(value)) {
				return t;
			}
		}
		
		throw new IllegalArgumentException("Value not found: " + value);
	}
	
	@Override
	public String toString(T value) {
		return value == null ? "" : toString.apply(value);
	}
	
	@Override
	public Node createGui(Stage stage, T value, Consumer<T> onAction) {
		if (validValues.size() > 32) return createTextField(value, onAction);
		if (validValues.size() == 16 || validValues.size() > 30 && value instanceof Integer) {
			VBox vbox = new VBox();
			for (int i = 0; i < 4; i++) {
				HBox hbox = new HBox();
				for (int j = 0; j < 8; j++) {
					final Integer n = i * 8 + j + 1;
					if (!validValues.contains(n)) continue;
					String selected = Integer.toString(n);
					ToggleButton button = new ToggleButton(selected);
					button.getStyleClass().add("property-list-validator-button");
					if (i == 0 && j == 0) button.getStyleClass().add("button-top-left");
					else if (i == 0 && j == 7) button.getStyleClass().add("button-top-right");
					else if (i == validValues.size() / 8 - 1 && j == 0) button.getStyleClass().add("button-bottom-left");
					else if (i == validValues.size() / 8 - 1 && j == 7) button.getStyleClass().add("button-bottom-right");
					button.setSelected(toString(value).equals(selected));
					button.setOnAction(event -> {
						try {
							onAction.accept((T) n);
						} catch (Exception exc) {
							exc.printStackTrace();
						}
						hbox.getChildren().forEach(node -> {
							if (node instanceof ToggleButton toggleButton) {
								toggleButton.setSelected(toggleButton == button);
							}
						});
					});
					hbox.getChildren().add(button);

				}
				vbox.getChildren().add(hbox);
			}
			return vbox;
		}
		int width = 0;
        for (T validValue : validValues) {
            width += validValue.toString().length() + 2;
        }
		return width > 36 ? createDropdown(value, onAction) : createHorizontalSelect(value, onAction);
	}

	TextField createTextField(T value, Consumer<T> onAction) {
		TextField textField = new TextField();
		textField.getStyleClass().add("property-list-validator-textfield");
		textField.setText(toString(value));
		textField.focusedProperty().addListener((observable, oldValue, newValue) -> {
			if (!newValue) {
				try {
					onAction.accept(parse(textField.getText()));
				} catch (Exception exc) {
					exc.printStackTrace();
				}
			}
		});
		return textField;
	}

	ComboBox<String> createDropdown(T value, Consumer<T> onAction) {
		HashSet<String> commonValues = new HashSet<>(Arrays.asList("1", "2", "4", "8", "16", "32"));
		ComboBox<String> valueList = new ComboBox<>();

		for (T t : validValues) {
			if (!commonValues.contains(t))
				valueList.getItems().add(toString.apply(t));
		}

		valueList.getStyleClass().add("property-list-validator-dropdown");

		valueList.setValue(toString(value));
		valueList.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
			if (!newValue.equals(oldValue)) {
				try {
					onAction.accept(parse(newValue));
				} catch (Exception exc) {
					exc.printStackTrace();
				}
			}
		});
		return valueList;
	}

	Node createHorizontalSelect(T value, Consumer<T> onAction) {
		HBox valueList = new HBox();
		String selected = toString(value);
		for (int i = 0; i < validValues.size(); i++) {
			T t = validValues.get(i);
			String text = toString.apply(t);
			ToggleButton button = new ToggleButton(text);
			valueList.getChildren().add(button);
			button.setSelected(text.equals(selected));
			button.setOnAction(event -> {
				try {
					onAction.accept(t);
				} catch (Exception exc) {
					exc.printStackTrace();
				}
				valueList.getChildren().forEach(node -> {
					ToggleButton toggleButton = (ToggleButton) node;
					toggleButton.setSelected(toggleButton == button);
				});
			});
			button.getStyleClass().add("property-list-validator-button");
			if (i == 0) button.getStyleClass().add("button-left");
			else if (i == validValues.size() - 1) button.getStyleClass().add("button-right");
		}
		valueList.getStyleClass().add("property-list-validator");

		return valueList;
	}
}