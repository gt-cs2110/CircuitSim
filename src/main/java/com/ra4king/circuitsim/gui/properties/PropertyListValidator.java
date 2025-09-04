package com.ra4king.circuitsim.gui.properties;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ra4king.circuitsim.gui.Properties.PropertyValidator;

import javafx.scene.Node;
import javafx.collections.ObservableList;
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
	
	@SuppressWarnings("unchecked")
	@Override
	public Node createGui(Stage stage, T value, Consumer<T> onAction) {
		int size = validValues.size();
		if (size > 33) return createTextField(value, onAction);
		if (value instanceof Integer) {
			boolean none = validValues.contains(-1);
			boolean zero = validValues.contains(0);
			if (none) {
				if (size > 9)
					return createDropdown(value, onAction);
				else
					return createHorizontalSelect(value, onAction);
			}
			VBox vbox = new VBox();
			for (int i = 0; i < 4; i++) {
				HBox hbox = new HBox();
				for (int j = 0; j < 8; j++) {
					final int n = i * 8 + j + (zero ? 0 : 1);
					if (!validValues.contains(n)) continue;
					String s = Integer.toString(n);
					ToggleButton button = new ToggleButton(s);

					ObservableList<String> style = button.getStyleClass();
					style.add("property-list-validator-button");

					if (size == 1) style.add("round-all");
					else if (size < 9) {
						if (j == 0) style.add("round-left");
						else if (j == 7) style.add("round-right");
					} else {
						if (i == 0 && j == 0) style.add("round-top-left");
						else if (i == 0 && j == 7) style.add("round-top-right");
						else if (i == (size - 1) / 8 && j == 0) style.add("round-bottom-left");
						else if (i == (size - 1) / 8 && j == 7) style.add("round-bottom-right");
					}

					button.setSelected(toString(value).equals(s));
					button.setOnAction(event -> {
						try {
							// Type warning suppressed here.
							// value is known to be an Integer at this point 
							// (due to instanceof check above)
							onAction.accept((T) (Integer) n);
						} catch (Exception e) {
							e.printStackTrace();
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
		ArrayList<ToggleButton> buttons = new ArrayList<>();
		String selected = toString(value);
		for (int i = 0; i < validValues.size(); i++) {
			T t = validValues.get(i);
			String text = toString.apply(t);
			ToggleButton button = new ToggleButton(text);
			buttons.add(button);
			button.setSelected(text.equals(selected));
			button.setOnAction(event -> {
				try {
					onAction.accept(t);
				} catch (Exception exc) {
					exc.printStackTrace();
				}
				buttons.forEach(node -> {
					ToggleButton toggleButton = (ToggleButton) node;
					toggleButton.setSelected(toggleButton == button);
				});
			});
			button.getStyleClass().add("property-list-validator-button");
			if (i == 0 && validValues.size() > 1) button.getStyleClass().add("button-left");
			else if (i == validValues.size() - 1 && validValues.size() > 1) button.getStyleClass().add("button-right");
		}
		return new HBox(buttons.toArray(new ToggleButton[0]));
	}
}
