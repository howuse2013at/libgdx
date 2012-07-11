
package com.badlogic.gdx.tools.imagepacker;

import java.util.Comparator;

import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.tools.imagepacker.TexturePacker2.Page;
import com.badlogic.gdx.tools.imagepacker.TexturePacker2.Rect;
import com.badlogic.gdx.tools.imagepacker.TexturePacker2.Settings;
import com.badlogic.gdx.utils.Array;

/** Packs pages of images using the maximal rectangles bin packing algorithm by Jukka Jylänki. A brute force binary search is used
 * to pack into the smallest bin possible.
 * @author Nathan Sweet */
public class MaxRectsPacker {
	private FreeRectChoiceHeuristic[] methods = FreeRectChoiceHeuristic.values();
	private MaxRects maxRects = new MaxRects();
	Settings settings;

	public MaxRectsPacker (Settings settings) {
		this.settings = settings;
		if (settings.minWidth > settings.maxWidth) throw new RuntimeException("Page min width cannot be higher than max width.");
		if (settings.minHeight > settings.maxHeight)
			throw new RuntimeException("Page min height cannot be higher than max height.");
	}

	public Array<Page> pack (Array<Rect> inputRects) {
		for (int i = 0, nn = inputRects.size; i < nn; i++) {
			Rect rect = inputRects.get(i);
			rect.width += settings.paddingX;
			rect.height += settings.paddingY;
		}

		if (settings.fast) {
			if (settings.rotation) {
				// Sort by longest side if rotation is enabled.
				inputRects.sort(new Comparator<Rect>() {
					public int compare (Rect o1, Rect o2) {
						int n1 = o1.width > o1.height ? o1.width : o1.height;
						int n2 = o2.width > o2.height ? o2.width : o2.height;
						return n2 - n1;
					}
				});
			} else {
				// Sort only by width (largest to smallest) if rotation is disabled.
				inputRects.sort(new Comparator<Rect>() {
					public int compare (Rect o1, Rect o2) {
						return o2.width - o1.width;
					}
				});
			}
		}

		Array<Page> pages = new Array();
		while (inputRects.size > 0) {
			Page result = packPage(inputRects);
			pages.add(result);
			inputRects = result.remainingRects;
		}
		return pages;
	}

	private Page packPage (Array<Rect> inputRects) {
		// Find min size.
		int minWidth = Integer.MAX_VALUE;
		int minHeight = Integer.MAX_VALUE;
		for (int i = 0, nn = inputRects.size; i < nn; i++) {
			Rect rect = inputRects.get(i);
			minWidth = Math.min(minWidth, rect.width);
			minHeight = Math.min(minHeight, rect.height);
			if (rect.width > settings.maxWidth && (!settings.rotation || rect.height > settings.maxWidth))
				throw new RuntimeException("Image does not fit with max page width " + settings.maxWidth + " and paddingX "
					+ settings.paddingX + ": " + rect);
			if (rect.height > settings.maxHeight && (!settings.rotation || rect.width > settings.maxHeight))
				throw new RuntimeException("Image does not fit in max page height " + settings.maxHeight + " and paddingY "
					+ settings.paddingY + ": " + rect);
		}
		minWidth = Math.max(minWidth, settings.minWidth);
		minHeight = Math.max(minHeight, settings.minHeight);

		System.out.print("Packing");

		// Find the minimal page size that fits all rects.
		BinarySearch widthSearch = new BinarySearch(minWidth, settings.maxWidth, settings.fast ? 25 : 15, settings.pot);
		BinarySearch heightSearch = new BinarySearch(minHeight, settings.maxHeight, settings.fast ? 25 : 15, settings.pot);
		int width = widthSearch.reset(), height = heightSearch.reset(), i = 0;
		Page bestResult = null;
		while (true) {
			Page bestWidthResult = null;
			while (width != -1) {
				Page result = packAtSize(true, width, height, inputRects);
				if (++i % 70 == 0) System.out.println();
				System.out.print(".");
				bestWidthResult = getBest(bestWidthResult, result);
				width = widthSearch.next(result == null);
			}
			bestResult = getBest(bestResult, bestWidthResult);
			height = heightSearch.next(bestWidthResult == null);
			if (height == -1) break;
			width = widthSearch.reset();
		}
		System.out.println();

		// Rects don't fit on one page. Fill a whole page and return.
		if (bestResult == null) bestResult = packAtSize(false, settings.maxWidth, settings.maxHeight, inputRects);

		return bestResult;
	}

	/** @param fully If true, the only results that pack all rects will be considered. If false, all results are considered, not all
	 *           rects may be packed. */
	private Page packAtSize (boolean fully, int width, int height, Array<Rect> inputRects) {
		Page bestResult = null;
		for (int i = 0, n = methods.length; i < n; i++) {
			maxRects.init(width, height);
			Page result;
			if (!settings.fast) {
				result = maxRects.pack(inputRects, methods[i]);
			} else {
				Array<Rect> remaining = new Array();
				for (int ii = 0, nn = inputRects.size; ii < nn; ii++) {
					Rect rect = inputRects.get(ii);
					if (maxRects.insert(rect, methods[i]) == null) {
						while (ii < nn)
							remaining.add(inputRects.get(ii++));
					}
				}
				result = maxRects.getResult();
				result.remainingRects = remaining;
			}
			if (fully && result.remainingRects.size > 0) continue;
			if (result.outputRects.size == 0) continue;
			bestResult = getBest(bestResult, result);
		}
		return bestResult;
	}

	private Page getBest (Page result1, Page result2) {
		if (result1 == null) return result2;
		if (result2 == null) return result1;
		return result1.occupancy > result2.occupancy ? result1 : result2;
	}

	static class BinarySearch {
		int min, max, fuzziness, low, high, current;
		boolean pot;

		public BinarySearch (int min, int max, int fuzziness, boolean pot) {
			this.pot = pot;
			this.fuzziness = pot ? 0 : fuzziness;
			this.min = pot ? (int)(Math.log(MathUtils.nextPowerOfTwo(min)) / Math.log(2)) : min;
			this.max = pot ? (int)(Math.log(MathUtils.nextPowerOfTwo(max)) / Math.log(2)) : max;
		}

		public int reset () {
			low = min;
			high = max;
			current = low + (high - low) / 2;
			return pot ? (int)Math.pow(2, current) : current;
		}

		public int next (boolean result) {
			if (low >= high) return -1;
			if (result)
				low = current + 1;
			else
				high = current - 1;
			current = low + (high - low) / 2;
			if (Math.abs(low - high) < fuzziness) return -1;
			return pot ? (int)Math.pow(2, current) : current;
		}
	}

	/** Maximal rectangles bin packing algorithm. Adapted from this C++ public domain source:
	 * http://clb.demon.fi/projects/even-more-rectangle-bin-packing
	 * @author Jukka Jyl�nki
	 * @author Nathan Sweet */
	class MaxRects {
		private int binWidth;
		private int binHeight;
		private final Array<Rect> usedRectangles = new Array();
		private final Array<Rect> freeRectangles = new Array();

		public void init (int width, int height) {
			binWidth = width;
			binHeight = height;

			usedRectangles.clear();
			freeRectangles.clear();
			Rect n = new Rect();
			n.x = 0;
			n.y = 0;
			n.width = width;
			n.height = height;
			freeRectangles.add(n);
		}

		/** Packs a single image. Order is defined externally. */
		public Rect insert (Rect rect, FreeRectChoiceHeuristic method) {
			Rect newNode = ScoreRect(rect, method);
			if (newNode.height == 0) return null;

			int numRectanglesToProcess = freeRectangles.size;
			for (int i = 0; i < numRectanglesToProcess; ++i) {
				if (SplitFreeNode(freeRectangles.get(i), newNode)) {
					freeRectangles.removeIndex(i);
					--i;
					--numRectanglesToProcess;
				}
			}

			PruneFreeList();

			usedRectangles.add(newNode);
			return newNode;
		}

		/** For each rectangle, packs each one then chooses the best and packs that. Slow! */
		public Page pack (Array<Rect> rects, FreeRectChoiceHeuristic method) {
			rects = new Array(rects);
			while (rects.size > 0) {
				int bestRectIndex = -1;
				Rect bestNode = new Rect();
				bestNode.score1 = Integer.MAX_VALUE;
				bestNode.score2 = Integer.MAX_VALUE;

				// Find the next rectangle that packs best.
				for (int i = 0; i < rects.size; i++) {
					Rect newNode = ScoreRect(rects.get(i), method);
					if (newNode.score1 < bestNode.score1 || (newNode.score1 == bestNode.score1 && newNode.score2 < bestNode.score2)) {
						bestNode.set(rects.get(i));
						bestNode.score1 = newNode.score1;
						bestNode.score2 = newNode.score2;
						bestNode.x = newNode.x;
						bestNode.y = newNode.y;
						bestNode.width = newNode.width;
						bestNode.height = newNode.height;
						bestNode.rotated = newNode.rotated;
						bestRectIndex = i;
					}
				}

				if (bestRectIndex == -1) break;

				PlaceRect(bestNode);
				rects.removeIndex(bestRectIndex);
			}

			Page result = getResult();
			result.remainingRects = rects;
			return result;
		}

		public Page getResult () {
			int w = 0, h = 0;
			for (int i = 0; i < usedRectangles.size; i++) {
				Rect rect = usedRectangles.get(i);
				w = Math.max(w, rect.x + rect.width);
				h = Math.max(h, rect.y + rect.height);
			}
			Page result = new Page();
			result.outputRects = new Array(usedRectangles);
			result.occupancy = getOccupancy();
			result.width = w;
			result.height = h;
			return result;
		}

		private void PlaceRect (Rect node) {
			int numRectanglesToProcess = freeRectangles.size;
			for (int i = 0; i < numRectanglesToProcess; i++) {
				if (SplitFreeNode(freeRectangles.get(i), node)) {
					freeRectangles.removeIndex(i);
					--i;
					--numRectanglesToProcess;
				}
			}

			PruneFreeList();

			usedRectangles.add(node);
		}

		private Rect ScoreRect (Rect rect, FreeRectChoiceHeuristic method) {
			int width = rect.width;
			int height = rect.height;
			int rotatedWidth = height - settings.paddingY + settings.paddingX;
			int rotatedHeight = width - settings.paddingX + settings.paddingY;
			boolean rotate = rect.canRotate && settings.rotation;

			Rect newNode = null;
			switch (method) {
			case BestShortSideFit:
				newNode = FindPositionForNewNodeBestShortSideFit(width, height, rotatedWidth, rotatedHeight, rotate);
				break;
			case BottomLeftRule:
				newNode = FindPositionForNewNodeBottomLeft(width, height, rotatedWidth, rotatedHeight, rotate);
				break;
			case ContactPointRule:
				newNode = FindPositionForNewNodeContactPoint(width, height, rotatedWidth, rotatedHeight, rotate);
				newNode.score1 = -newNode.score1; // Reverse since we are minimizing, but for contact point score bigger is better.
				break;
			case BestLongSideFit:
				newNode = FindPositionForNewNodeBestLongSideFit(width, height, rotatedWidth, rotatedHeight, rotate);
				break;
			case BestAreaFit:
				newNode = FindPositionForNewNodeBestAreaFit(width, height, rotatedWidth, rotatedHeight, rotate);
				break;
			}

			// Cannot fit the current rectangle.
			if (newNode.height == 0) {
				newNode.score1 = Integer.MAX_VALUE;
				newNode.score2 = Integer.MAX_VALUE;
			}

			return newNode;
		}

		// / Computes the ratio of used surface area.
		private float getOccupancy () {
			int usedSurfaceArea = 0;
			for (int i = 0; i < usedRectangles.size; i++)
				usedSurfaceArea += usedRectangles.get(i).width * usedRectangles.get(i).height;
			return (float)usedSurfaceArea / (binWidth * binHeight);
		}

		private Rect FindPositionForNewNodeBottomLeft (int width, int height, int rotatedWidth, int rotatedHeight, boolean rotate) {
			Rect bestNode = new Rect();

			bestNode.score1 = Integer.MAX_VALUE; // best y, score2 is best x

			for (int i = 0; i < freeRectangles.size; i++) {
				// Try to place the rectangle in upright (non-rotated) orientation.
				if (freeRectangles.get(i).width >= width && freeRectangles.get(i).height >= height) {
					int topSideY = freeRectangles.get(i).y + height;
					if (topSideY < bestNode.score1 || (topSideY == bestNode.score1 && freeRectangles.get(i).x < bestNode.score2)) {
						bestNode.x = freeRectangles.get(i).x;
						bestNode.y = freeRectangles.get(i).y;
						bestNode.width = width;
						bestNode.height = height;
						bestNode.score1 = topSideY;
						bestNode.score2 = freeRectangles.get(i).x;
						bestNode.rotated = false;
					}
				}
				if (rotate && freeRectangles.get(i).width >= rotatedWidth && freeRectangles.get(i).height >= rotatedHeight) {
					int topSideY = freeRectangles.get(i).y + rotatedHeight;
					if (topSideY < bestNode.score1 || (topSideY == bestNode.score1 && freeRectangles.get(i).x < bestNode.score2)) {
						bestNode.x = freeRectangles.get(i).x;
						bestNode.y = freeRectangles.get(i).y;
						bestNode.width = rotatedWidth;
						bestNode.height = rotatedHeight;
						bestNode.score1 = topSideY;
						bestNode.score2 = freeRectangles.get(i).x;
						bestNode.rotated = true;
					}
				}
			}
			return bestNode;
		}

		private Rect FindPositionForNewNodeBestShortSideFit (int width, int height, int rotatedWidth, int rotatedHeight,
			boolean rotate) {
			Rect bestNode = new Rect();
			bestNode.score1 = Integer.MAX_VALUE;

			for (int i = 0; i < freeRectangles.size; i++) {
				// Try to place the rectangle in upright (non-rotated) orientation.
				if (freeRectangles.get(i).width >= width && freeRectangles.get(i).height >= height) {
					int leftoverHoriz = Math.abs(freeRectangles.get(i).width - width);
					int leftoverVert = Math.abs(freeRectangles.get(i).height - height);
					int shortSideFit = Math.min(leftoverHoriz, leftoverVert);
					int longSideFit = Math.max(leftoverHoriz, leftoverVert);

					if (shortSideFit < bestNode.score1 || (shortSideFit == bestNode.score1 && longSideFit < bestNode.score2)) {
						bestNode.x = freeRectangles.get(i).x;
						bestNode.y = freeRectangles.get(i).y;
						bestNode.width = width;
						bestNode.height = height;
						bestNode.score1 = shortSideFit;
						bestNode.score2 = longSideFit;
						bestNode.rotated = false;
					}
				}

				if (rotate && freeRectangles.get(i).width >= rotatedWidth && freeRectangles.get(i).height >= rotatedHeight) {
					int flippedLeftoverHoriz = Math.abs(freeRectangles.get(i).width - rotatedWidth);
					int flippedLeftoverVert = Math.abs(freeRectangles.get(i).height - rotatedHeight);
					int flippedShortSideFit = Math.min(flippedLeftoverHoriz, flippedLeftoverVert);
					int flippedLongSideFit = Math.max(flippedLeftoverHoriz, flippedLeftoverVert);

					if (flippedShortSideFit < bestNode.score1
						|| (flippedShortSideFit == bestNode.score1 && flippedLongSideFit < bestNode.score2)) {
						bestNode.x = freeRectangles.get(i).x;
						bestNode.y = freeRectangles.get(i).y;
						bestNode.width = rotatedWidth;
						bestNode.height = rotatedHeight;
						bestNode.score1 = flippedShortSideFit;
						bestNode.score2 = flippedLongSideFit;
						bestNode.rotated = true;
					}
				}
			}

			return bestNode;
		}

		private Rect FindPositionForNewNodeBestLongSideFit (int width, int height, int rotatedWidth, int rotatedHeight,
			boolean rotate) {
			Rect bestNode = new Rect();

			bestNode.score2 = Integer.MAX_VALUE;

			for (int i = 0; i < freeRectangles.size; i++) {
				// Try to place the rectangle in upright (non-rotated) orientation.
				if (freeRectangles.get(i).width >= width && freeRectangles.get(i).height >= height) {
					int leftoverHoriz = Math.abs(freeRectangles.get(i).width - width);
					int leftoverVert = Math.abs(freeRectangles.get(i).height - height);
					int shortSideFit = Math.min(leftoverHoriz, leftoverVert);
					int longSideFit = Math.max(leftoverHoriz, leftoverVert);

					if (longSideFit < bestNode.score2 || (longSideFit == bestNode.score2 && shortSideFit < bestNode.score1)) {
						bestNode.x = freeRectangles.get(i).x;
						bestNode.y = freeRectangles.get(i).y;
						bestNode.width = width;
						bestNode.height = height;
						bestNode.score1 = shortSideFit;
						bestNode.score2 = longSideFit;
						bestNode.rotated = false;
					}
				}

				if (rotate && freeRectangles.get(i).width >= rotatedWidth && freeRectangles.get(i).height >= rotatedHeight) {
					int leftoverHoriz = Math.abs(freeRectangles.get(i).width - rotatedWidth);
					int leftoverVert = Math.abs(freeRectangles.get(i).height - rotatedHeight);
					int shortSideFit = Math.min(leftoverHoriz, leftoverVert);
					int longSideFit = Math.max(leftoverHoriz, leftoverVert);

					if (longSideFit < bestNode.score2 || (longSideFit == bestNode.score2 && shortSideFit < bestNode.score1)) {
						bestNode.x = freeRectangles.get(i).x;
						bestNode.y = freeRectangles.get(i).y;
						bestNode.width = rotatedWidth;
						bestNode.height = rotatedHeight;
						bestNode.score1 = shortSideFit;
						bestNode.score2 = longSideFit;
						bestNode.rotated = true;
					}
				}
			}
			return bestNode;
		}

		private Rect FindPositionForNewNodeBestAreaFit (int width, int height, int rotatedWidth, int rotatedHeight, boolean rotate) {
			Rect bestNode = new Rect();

			bestNode.score1 = Integer.MAX_VALUE; // best area fit, score2 is best short side fit

			for (int i = 0; i < freeRectangles.size; i++) {
				int areaFit = freeRectangles.get(i).width * freeRectangles.get(i).height - width * height;

				// Try to place the rectangle in upright (non-rotated) orientation.
				if (freeRectangles.get(i).width >= width && freeRectangles.get(i).height >= height) {
					int leftoverHoriz = Math.abs(freeRectangles.get(i).width - width);
					int leftoverVert = Math.abs(freeRectangles.get(i).height - height);
					int shortSideFit = Math.min(leftoverHoriz, leftoverVert);

					if (areaFit < bestNode.score1 || (areaFit == bestNode.score1 && shortSideFit < bestNode.score2)) {
						bestNode.x = freeRectangles.get(i).x;
						bestNode.y = freeRectangles.get(i).y;
						bestNode.width = width;
						bestNode.height = height;
						bestNode.score2 = shortSideFit;
						bestNode.score1 = areaFit;
						bestNode.rotated = false;
					}
				}

				if (rotate && freeRectangles.get(i).width >= rotatedWidth && freeRectangles.get(i).height >= rotatedHeight) {
					int leftoverHoriz = Math.abs(freeRectangles.get(i).width - rotatedWidth);
					int leftoverVert = Math.abs(freeRectangles.get(i).height - rotatedHeight);
					int shortSideFit = Math.min(leftoverHoriz, leftoverVert);

					if (areaFit < bestNode.score1 || (areaFit == bestNode.score1 && shortSideFit < bestNode.score2)) {
						bestNode.x = freeRectangles.get(i).x;
						bestNode.y = freeRectangles.get(i).y;
						bestNode.width = rotatedWidth;
						bestNode.height = rotatedHeight;
						bestNode.score2 = shortSideFit;
						bestNode.score1 = areaFit;
						bestNode.rotated = true;
					}
				}
			}
			return bestNode;
		}

		// / Returns 0 if the two intervals i1 and i2 are disjoint, or the length of their overlap otherwise.
		private int CommonIntervalLength (int i1start, int i1end, int i2start, int i2end) {
			if (i1end < i2start || i2end < i1start) return 0;
			return Math.min(i1end, i2end) - Math.max(i1start, i2start);
		}

		private int ContactPointScoreNode (int x, int y, int width, int height) {
			int score = 0;

			if (x == 0 || x + width == binWidth) score += height;
			if (y == 0 || y + height == binHeight) score += width;

			for (int i = 0; i < usedRectangles.size; i++) {
				if (usedRectangles.get(i).x == x + width || usedRectangles.get(i).x + usedRectangles.get(i).width == x)
					score += CommonIntervalLength(usedRectangles.get(i).y, usedRectangles.get(i).y + usedRectangles.get(i).height, y,
						y + height);
				if (usedRectangles.get(i).y == y + height || usedRectangles.get(i).y + usedRectangles.get(i).height == y)
					score += CommonIntervalLength(usedRectangles.get(i).x, usedRectangles.get(i).x + usedRectangles.get(i).width, x, x
						+ width);
			}
			return score;
		}

		private Rect FindPositionForNewNodeContactPoint (int width, int height, int rotatedWidth, int rotatedHeight, boolean rotate) {
			Rect bestNode = new Rect();

			bestNode.score1 = -1; // best contact score

			for (int i = 0; i < freeRectangles.size; i++) {
				// Try to place the rectangle in upright (non-rotated) orientation.
				if (freeRectangles.get(i).width >= width && freeRectangles.get(i).height >= height) {
					int score = ContactPointScoreNode(freeRectangles.get(i).x, freeRectangles.get(i).y, width, height);
					if (score > bestNode.score1) {
						bestNode.x = freeRectangles.get(i).x;
						bestNode.y = freeRectangles.get(i).y;
						bestNode.width = width;
						bestNode.height = height;
						bestNode.score1 = score;
						bestNode.rotated = false;
					}
				}
				if (rotate && freeRectangles.get(i).width >= rotatedWidth && freeRectangles.get(i).height >= rotatedHeight) {
					// This was width,height -- bug fixed?
					int score = ContactPointScoreNode(freeRectangles.get(i).x, freeRectangles.get(i).y, rotatedWidth, rotatedHeight);
					if (score > bestNode.score1) {
						bestNode.x = freeRectangles.get(i).x;
						bestNode.y = freeRectangles.get(i).y;
						bestNode.width = rotatedWidth;
						bestNode.height = rotatedHeight;
						bestNode.score1 = score;
						bestNode.rotated = true;
					}
				}
			}
			return bestNode;
		}

		private boolean SplitFreeNode (Rect freeNode, Rect usedNode) {
			// Test with SAT if the rectangles even intersect.
			if (usedNode.x >= freeNode.x + freeNode.width || usedNode.x + usedNode.width <= freeNode.x
				|| usedNode.y >= freeNode.y + freeNode.height || usedNode.y + usedNode.height <= freeNode.y) return false;

			if (usedNode.x < freeNode.x + freeNode.width && usedNode.x + usedNode.width > freeNode.x) {
				// New node at the top side of the used node.
				if (usedNode.y > freeNode.y && usedNode.y < freeNode.y + freeNode.height) {
					Rect newNode = new Rect(freeNode);
					newNode.height = usedNode.y - newNode.y;
					freeRectangles.add(newNode);
				}

				// New node at the bottom side of the used node.
				if (usedNode.y + usedNode.height < freeNode.y + freeNode.height) {
					Rect newNode = new Rect(freeNode);
					newNode.y = usedNode.y + usedNode.height;
					newNode.height = freeNode.y + freeNode.height - (usedNode.y + usedNode.height);
					freeRectangles.add(newNode);
				}
			}

			if (usedNode.y < freeNode.y + freeNode.height && usedNode.y + usedNode.height > freeNode.y) {
				// New node at the left side of the used node.
				if (usedNode.x > freeNode.x && usedNode.x < freeNode.x + freeNode.width) {
					Rect newNode = new Rect(freeNode);
					newNode.width = usedNode.x - newNode.x;
					freeRectangles.add(newNode);
				}

				// New node at the right side of the used node.
				if (usedNode.x + usedNode.width < freeNode.x + freeNode.width) {
					Rect newNode = new Rect(freeNode);
					newNode.x = usedNode.x + usedNode.width;
					newNode.width = freeNode.x + freeNode.width - (usedNode.x + usedNode.width);
					freeRectangles.add(newNode);
				}
			}

			return true;
		}

		private void PruneFreeList () {
			/*
			 * /// Would be nice to do something like this, to avoid a Theta(n^2) loop through each pair. /// But unfortunately it
			 * doesn't quite cut it, since we also want to detect containment. /// Perhaps there's another way to do this faster than
			 * Theta(n^2).
			 * 
			 * if (freeRectangles.size > 0) clb::sort::QuickSort(&freeRectangles[0], freeRectangles.size, NodeSortCmp);
			 * 
			 * for(int i = 0; i < freeRectangles.size-1; i++) if (freeRectangles[i].x == freeRectangles[i+1].x && freeRectangles[i].y
			 * == freeRectangles[i+1].y && freeRectangles[i].width == freeRectangles[i+1].width && freeRectangles[i].height ==
			 * freeRectangles[i+1].height) { freeRectangles.erase(freeRectangles.begin() + i); --i; }
			 */

			// / Go through each pair and remove any rectangle that is redundant.
			for (int i = 0; i < freeRectangles.size; i++)
				for (int j = i + 1; j < freeRectangles.size; ++j) {
					if (IsContainedIn(freeRectangles.get(i), freeRectangles.get(j))) {
						freeRectangles.removeIndex(i);
						--i;
						break;
					}
					if (IsContainedIn(freeRectangles.get(j), freeRectangles.get(i))) {
						freeRectangles.removeIndex(j);
						--j;
					}
				}
		}

		private boolean IsContainedIn (Rect a, Rect b) {
			return a.x >= b.x && a.y >= b.y && a.x + a.width <= b.x + b.width && a.y + a.height <= b.y + b.height;
		}
	}

	static public enum FreeRectChoiceHeuristic {
		// BSSF: Positions the rectangle against the short side of a free rectangle into which it fits the best.
		BestShortSideFit,
		// BLSF: Positions the rectangle against the long side of a free rectangle into which it fits the best.
		BestLongSideFit,
		// BAF: Positions the rectangle into the smallest free rect into which it fits.
		BestAreaFit,
		// BL: Does the Tetris placement.
		BottomLeftRule,
		// CP: Choosest the placement where the rectangle touches other rects as much as possible.
		ContactPointRule
	};
}
