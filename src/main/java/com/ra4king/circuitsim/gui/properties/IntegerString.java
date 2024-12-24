package com.ra4king.circuitsim.gui.properties;

import java.util.Objects;

import com.ra4king.circuitsim.simulator.SimulationException;
import com.ra4king.circuitsim.gui.Properties.PropertyValidator;

/**
 * A wrapper around integers which can be constructed from strings of different bases.
 * 
 * @author Roi Atalla
 */
public final class IntegerString {
	private final String valueString;
	private final int value;
	private final int base;
	
	/**
	 * Creates an integer out of a string.
	 * This infers the base of the integer using the string prefix:
	 * - '#' or no prefix indicates a decimal number,
	 * - '0x' or 'x' indicates a hexadecimal integer,
	 * - '0b' or 'b' indicates a binary number.
	 * 
	 * @param valueString the string to parse
	 */
	public IntegerString(String valueString) {
		this(valueString, 10);
	}
	/**
	 * Creates an integer out of a string.
	 * 
	 * For prefixed integer strings (e.g., '0xDEADBEEF', '0b100000111110', '#123'),
	 * the base is inferred.
	 * 
	 * If there is no prefix, then the base defaults to the defaultBase parameter.
	 * 
	 * @param valueString the string to parse
	 * @param defaultBase the default base, if no prefix provided
	 */
	public IntegerString(String valueString, int defaultBase) {
		int base = defaultBase;
		boolean isNegative = valueString.startsWith("-");
		
		// Compute negative and base-override, if prefixes are present:
		if (isNegative) {
			valueString = valueString.substring(1);
		}
		if (valueString.startsWith("0x")) {
			base = 16;
			valueString = valueString.substring(2);
		} else if (valueString.startsWith("x")) {
			base = 16;
			valueString = valueString.substring(1);
		} else if (valueString.startsWith("0b")) {
			base = 2;
			valueString = valueString.substring(2);
		} else if (valueString.startsWith("b")) {
			base = 2;
			valueString = valueString.substring(1);
		} else if (valueString.startsWith("#")) {
			base = 10;
			valueString = valueString.substring(1);
		}
		
		// Parse value:
		try {
			this.value = (isNegative ? -1 : 1) * Integer.parseInt(valueString, base);
		} catch (NumberFormatException exc) {
			throw new SimulationException(valueString + " is not a valid value of base " + base);
		}
		this.base = base;
		// Readd prefixes (note this converts 'x' and 'b' to '0x' and '0b'):
		String negPrefix = isNegative ? "-" : "";
		String basePrefix = "";
		if (base != defaultBase) {
			basePrefix = switch (base) {
				case 2 -> "0b";
				case 10 -> "#";
				case 16 -> "0x";
				default -> "";
			};
		}
		this.valueString = negPrefix + basePrefix + valueString;
	}
	
	/**
	 * Creates a new `IntegerString` from a base-10 integer.
	 * @param value the integer
	 */
	public IntegerString(int value) {
		this.value = value;
		this.base = 10;
		this.valueString = Integer.toString(value);
	}
	
	public int getValue() {
		return value;
	}
	
	public int getBase() {
		return base;
	}
	
	/**
	 * @return the string representation of this value (with an explicit prefix)
	 */
	public String prefixedString() {
		return switch (base) {
			case 2  -> String.format("0b%s", Integer.toString(value, 2));
			case 10 -> String.format("#%d", value);
			case 16 -> String.format("0x%X", value);
			default -> Integer.toString(value);
		};
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof IntegerString) {
			IntegerString other = (IntegerString) o;
			return this.value == other.value && this.base == other.base;
		}
		return false;
	}
	
	@Override
	public int hashCode() {
		return Objects.hash(value, base);
	}
	
	@Override
	public String toString() {
		return this.valueString;
	}

	public static final class IntegerStringValidator implements PropertyValidator<IntegerString> {
		private final int base;
		public IntegerStringValidator() {
			this(10);
		}
		public IntegerStringValidator(int base) {
			this.base = base;
		}

		@Override
		public int hashCode() {
			return Integer.hashCode(this.base);
		}
		
		@Override
		public boolean equals(Object other) {
			if (other instanceof IntegerStringValidator) {
				IntegerStringValidator validator = (IntegerStringValidator) other;
				return this.base == validator.base;
			}
			
			return true;
		}
		@Override
		public IntegerString parse(String value) {
			return new IntegerString(value, this.base);
		}
	}
}
