package com.ra4king.circuitsim.gui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;

import com.ra4king.circuitsim.gui.LinkWires.Wire;

/**
 * @author Roi Atalla
 */
public final class PathFinding {
	private PathFinding() {}

	/**
	 * Value that is returned in a {@link PathFinding.ValidWireLocation} callback.
	 */
	public enum LocationPreference {
		/**
		 * Under no circumstances may this point be part of the wire path.
		 */
		INVALID,
		/**
		 * This point may be part of the wire path.
		 */
		VALID,
		/**
		 * This point should be part of the wire path.
		 */
		PREFER
	}
	
	/**
	 * Callback for generating whether a given point should be allowed to be part of a path.
	 */
	@FunctionalInterface
	public interface ValidWireLocation {
		LocationPreference isValidWireLocation(int x, int y, boolean horizontal);
	}
	
	/**
	 * Computes the best path between the source point and destination point.
	 * @param sx source x
	 * @param sy source y
	 * @param dx destination x
	 * @param dy destination y
	 * @param callback a callback which indicates 
	 *     whether a wire can be placed at a given point and orientation
	 * @return the set of wires that form the best path between the two points
	 */
	public static Set<Wire> bestPath(int sx, int sy, int dx, int dy, ValidWireLocation callback) {
		if (dx < 0 || dy < 0) {
			return Set.of();
		}
		
		Point source = new Point(sx, sy);
		Point destination = new Point(dx, dy);
		
		// Map of points to their corresponding parent point.
		// 
		// Also acts as the visited set.
		// If a key exists in this map, it is considered "visited".
		Map<Point, Point> parents = new HashMap<>();
		PriorityQueue<Node> frontier = new PriorityQueue<>();
		
		frontier.add(new Node(new Cost(0, 0, 0), source, null, null));
		int iterations = 0;
		while (!frontier.isEmpty()) {
			if (Thread.currentThread().isInterrupted()) {
				return Set.of();
			}
			
			iterations++;
			if (iterations == 5000) {
				System.err.println("Path finding taking too long, bail...");
				return Set.of();
			}

			Node current = frontier.poll();
			// Skip if already visited
			if (parents.containsKey(current.point)) {
				continue;
			}
			parents.put(current.point, current.parent);

			if (destination.equals(current.point)) {
				return constructPath(parents, current.point);
			}

			// Add neighbors
			for (Direction direction : Direction.values()) {
				// Filter opposite direction (cause wires can't move backwards)
				// and out-of-bounds neighbors.
				if (direction.getOpposite() == current.directionEntered) {
					continue;
				}
				Point neighbor = direction.move(current.point);
				if (neighbor.x < 0 || neighbor.y < 0) {
					continue;
				}

				LocationPreference preference = callback.isValidWireLocation(neighbor.x, neighbor.y, direction.isHorizontal());
				if (preference == LocationPreference.INVALID) {
					continue;
				}

				int additionalLength = switch (preference) {
					case PREFER -> 0;
					case VALID -> 1;
					case INVALID -> 999;
				};
				
				int additionalTurns = 0;
				if (current.directionEntered != null && direction != current.directionEntered) {
					if (neighbor.x == destination.x || neighbor.y == destination.y) {
						additionalTurns = 1;
					} else {
						additionalTurns = 2;
					}
				}
				
				Cost totalCost = new Cost(
					current.cost.length + additionalLength,
					current.cost.turns + additionalTurns,
					neighbor.manhattanDistance(destination)
				);
				frontier.add(new Node(totalCost, neighbor, current.point, direction));
			}
		}

		System.err.println("No possible paths found...");
		return null;
	}

	private static Set<Wire> constructPath(Map<Point, Point> cameFrom, Point current) {
		Set<Wire> totalPath = new HashSet<>();
		
		Point lastEndpoint = current;
		Point next = cameFrom.get(current);
		while (next != null) {
			if (!(lastEndpoint.x == current.x && current.x == next.x) &&
			    !(lastEndpoint.y == current.y && current.y == next.y)) {
				int len = (current.x - lastEndpoint.x) + (current.y - lastEndpoint.y);
				totalPath.add(new Wire(null, lastEndpoint.x, lastEndpoint.y, len, lastEndpoint.y == current.y));
				lastEndpoint = current;
			}
			
			current = next;
			next = cameFrom.get(current);
		}
		
		int len = (current.x - lastEndpoint.x) + (current.y - lastEndpoint.y);
		if (len != 0) {
			totalPath.add(new Wire(null, lastEndpoint.x, lastEndpoint.y, len, lastEndpoint.y == current.y));
		}
		
		return totalPath;
	}
	
	private enum Direction {
		RIGHT(1, 0), LEFT(-1, 0), DOWN(0, 1), UP(0, -1);
		
		private int deltaX;
		private int deltaY;
		private Direction(int deltaX, int deltaY) {
			this.deltaX = deltaX;
			this.deltaY = deltaY;
		}

		public Direction getOpposite() {
			return switch (this) {
				case RIGHT -> LEFT;
				case LEFT -> RIGHT;
				case DOWN -> UP;
				case UP -> DOWN;
			};
		}
		public boolean isHorizontal() {
			return this == LEFT || this == RIGHT;
		}
		public Point move(Point point) {
			return new Point(point.x + deltaX, point.y + deltaY);
		}
	}
	
	public static final class Point {
		public final int x;
		public final int y;
		
		Point(int x, int y) {
			this.x = x;
			this.y = y;
		}
		
		public int manhattanDistance(Point other) {
			int dx = this.x - other.x;
			int dy = this.y - other.y;
			return Math.absExact(dx) + Math.absExact(dy);
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(x, y);
		}
		
		@Override
		public boolean equals(Object other) {
			if (other instanceof Point) {
				Point element = (Point)other;
				return element.x == x && element.y == y;
			}
			
			return false;
		}
		
		@Override
		public String toString() {
			return "Point(x = " + x + ", y = " + y + ")";
		}
	}
	
	private static final class Cost implements Comparable<Cost> {
		private final int length;
		private final int turns;
		private final int heuristicCost;
		
		Cost(int length, int turns, int heuristic) {
			this.length = length;
			this.turns = turns;
			this.heuristicCost = heuristic;
		}
		
		public int score() {
			return this.turns * 5 + this.length + this.heuristicCost;
		}

		@Override
		public int compareTo(Cost other) {
			return Integer.compare(this.score(), other.score());
		}
		
		@Override
		public int hashCode() {
			return Objects.hash(length, turns, heuristicCost);
		}
		
		@Override
		public boolean equals(Object other) {
			if (other instanceof Cost) {
				Cost element = (Cost)other;
				return element.length == length 
					&& element.turns == turns 
					&& element.heuristicCost == heuristicCost;
			}
			
			return false;
		}
		
		@Override
		public String toString() {
			return String.format(
				"Cost(length = %s, turns = %s, heuristicCost = %s)",
				length, turns, heuristicCost
			);
		}
	}

	private static final class Node implements Comparable<Node> {
		private final Cost cost;
		private final Point point;
		private final Point parent;
		private final Direction directionEntered;
		public Node(Cost cost, Point point, Point parent, Direction directionEntered) {
			this.cost = cost;
			this.point = point;
			this.parent = parent;
			this.directionEntered = directionEntered;
		}

		// Comparisons are only based on cost.
		// The rest is extra data which tags along.
		@Override
		public int compareTo(Node o) {
			return this.cost.compareTo(o.cost);
		}
		@Override
		public boolean equals(Object other) {
			if (other instanceof Node) {
				Node element = (Node)other;
				return this.cost.equals(element.cost);
			}
			
			return false;
		}
		@Override
		public int hashCode() {
			return Objects.hash(cost);
		}
	}
}
