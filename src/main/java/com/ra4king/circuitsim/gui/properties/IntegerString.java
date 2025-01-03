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
	private final boolean prefixed;
	
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
		boolean prefixed = false;

		// Compute negative and base-override, if prefixes are present:
		if (isNegative) {
			valueString = valueString.substring(1);
		}
		if (valueString.startsWith("0x")) {
			base = 16;
			prefixed = true;
			valueString = valueString.substring(2);
		} else if (valueString.startsWith("x")) {
			base = 16;
			prefixed = true;
			valueString = valueString.substring(1);
		} else if (valueString.startsWith("0b")) {
			base = 2;
			prefixed = true;
			valueString = valueString.substring(2);
		} else if (valueString.startsWith("b")) {
			base = 2;
			prefixed = true;
			valueString = valueString.substring(1);
		} else if (valueString.startsWith("#")) {
			base = 10;
			prefixed = true;
			valueString = valueString.substring(1);
		}
		// Check negative after prefix
		if (!isNegative && valueString.startsWith("-")) {
			isNegative = true;
			valueString = valueString.substring(1);
		}

		// Parse value:
		try {
			this.value = (isNegative ? -1 : 1) * Integer.parseUnsignedInt(valueString, base);
		} catch (NumberFormatException exc) {
			throw new SimulationException(valueString + " is not a valid value of base " + base);
		}
		this.base = base;
		this.prefixed = prefixed;
		// Readd prefixes (note this converts 'x' and 'b' to '0x' and '0b'):
		String negPrefix = isNegative ? "-" : "";
		String basePrefix = "";
		if (prefixed) {
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
		this.prefixed = false;
		this.valueString = Integer.toString(value);
	}
	
	/**
	 * Similar to default parsing, 
	 * but unprefixed values are parsed as base-10 and converted to their display base.
	 * 
	 * @param valueString the string to parse
	 * @param defaultBase the display base
	 */
	public static IntegerString parseFromLegacy(String valueString, int base) {
		// Cast value and force prefix:
		IntegerString valStr = new IntegerString(valueString, 10);
		String string = valStr.toString(base);
		if (!valStr.prefixed) {
			string = switch (base) {
				case 2 -> "0b" + string;
				case 16 -> "0x" + string;
				default -> string;
			};
		}
		
		return new IntegerString(string, base);
	}

	public int getValue() {
		return value;
	}
	
	public int getBase() {
		return base;
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

	/**
	 * Casts this integer string to a string of a different base.
	 * If the integer string is prefixed, then this doesn't change the string.
	 * 
	 * This also does not prefix the string.
	 * 
	 * @param radix Base to cast to string
	 * @return the string representation
	 */
	public String toString(int radix) {
		if (this.prefixed) return this.valueString;
		return Integer.toString(this.value, radix);
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
