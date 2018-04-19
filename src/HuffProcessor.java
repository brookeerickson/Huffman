import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Interface that all compression suites must implement. That is they must be
 * able to compress a file and also reverse/decompress that process.
 * 
 * @author Brian Lavallee
 * @since 5 November 2015
 * @author Owen Atrachan
 * @since December 1, 2016
 */
public class HuffProcessor {

	public static final int BITS_PER_WORD = 8;
	public static final int BITS_PER_INT = 32;
	public static final int ALPH_SIZE = (1 << BITS_PER_WORD); // shift left BITS_PER_WORD times (8) so 2^8 or 256
	public static final int PSEUDO_EOF = ALPH_SIZE;
	// 0-9, a:10, b, c, d, e, f:15
	public static final int HUFF_NUMBER = 0xface8200;
	public static final int HUFF_TREE = HUFF_NUMBER | 1; // change last bit to 1
	public static final int HUFF_COUNTS = HUFF_NUMBER | 2;

	public enum Header {
		TREE_HEADER, COUNT_HEADER
	};

	public Header myHeader = Header.TREE_HEADER;

	/**
	 * Compresses a file. Process must be reversible and loss-less.
	 *
	 * @param in
	 *            Buffered bit stream of the file to be compressed.
	 * @param out
	 *            Buffered bit stream writing to the output file.
	 */
	// char is 8 bits
	// int is 32 bits
	public void compress(BitInputStream in, BitOutputStream out) {
		// readForCounts - counts num occurances of each char
		// set of things you can read btwn 0,255 when see char implement count
		// makeTreeFromCounts constructs huffman tree
		// if internal node, just 1 bit, if leaf node put 1 bit and value
		// makeCodingsFromTree - generates econdings for tree (LeaftoTrails APT)
		// writeHeader - magic number and preorder traversal
		// writeCompressedBits - says im done (psuedo end of file character) put once
		// into tree
		int[] counts = readForCounts(in);
		HuffNode root = makeTreeFromCounts(counts);
		String[] codings = makeCodingsFromTree(root);
		writeHeader(root, out);
		in.reset();
		writeCompressedBits(in, codings, out);
	}

	private int[] readForCounts(BitInputStream in) {
		int[] ret = new int[256];
		int val = in.readBits(BITS_PER_WORD);
		while (val != -1) {
			ret[val] = ret[val]+1;
			val = in.readBits(BITS_PER_WORD);
		}
		//System.out.println(Arrays.toString(ret));
		return ret;
	}

	private HuffNode makeTreeFromCounts(int[] ret) {
		PriorityQueue<HuffNode> pq = new PriorityQueue<>();
		int alphacount = 0;
		for (int i = 0; i < ret.length; i++) {
			if (ret[i] != 0) {
				pq.add(new HuffNode(i, ret[i]));
				alphacount++;
			}
		}
		System.out.println(alphacount);
		pq.add(new HuffNode(PSEUDO_EOF, 1));
//		for (HuffNode each:pq) {
//			System.out.println(each.toString());
//		}
		//System.out.println("next");
		while (pq.size() > 1) {
			HuffNode left = pq.remove();
//			System.out.println("left" + left.toString());
			HuffNode right = pq.remove();
//			System.out.println("right" + right.toString());
			HuffNode t = new HuffNode(-1, left.weight() + right.weight(), left, right);
			pq.add(t);
		}
		HuffNode root = pq.remove();
		//System.out.println(root.toString());
		return root;
	}

	private String[] makeCodingsFromTree(HuffNode node) {
		String[] codings = new String[257];
		codings = makeCodingsFromTreeHelper(node, "", codings);
		return codings;
	}

	private String[] makeCodingsFromTreeHelper(HuffNode node, String path, String[] codings) {
		if (node != null) {
			if (node.left() == null && node.right() == null) {
				codings[node.value()] = path;
			} else if (node.left() == null && node.right() != null) {
				codings = makeCodingsFromTreeHelper(node.right(), path + "1", codings);
			} else if (node.left() != null && node.right() == null) {
				codings = makeCodingsFromTreeHelper(node.left(), path + "0", codings);
			} else if (node.left() != null && node.right() != null) {
				codings = makeCodingsFromTreeHelper(node.left(), path + "0", codings);
				codings = makeCodingsFromTreeHelper(node.right(), path + "1", codings);
			}
		}
		//System.out.println(Arrays.toString(codings));
		return codings;
	}

	private void writeHeader(HuffNode node, BitOutputStream out) {
		out.writeBits(BITS_PER_INT, HUFF_TREE);
		writeTree(node, out);
	}

	private void writeTree(HuffNode node, BitOutputStream out) {
		if (node != null) {
			if (node.left() == null && node.right() == null) {
				out.writeBits(1, 1);
				out.writeBits(BITS_PER_WORD + 1, node.value());}
		 else
			out.writeBits(1, 0);
			writeTree(node.left(), out);
			writeTree(node.right(), out);
		}
	}

	private void writeCompressedBits(BitInputStream in, String[] encodings, BitOutputStream out) {
		while (true) {
			int val = in.readBits(BITS_PER_WORD);
			if (val == -1)
				break;
			out.writeBits(encodings[val].length(), Integer.parseInt(encodings[val], 2));
		}
		out.writeBits(encodings[PSEUDO_EOF].length(), Integer.parseInt(encodings[PSEUDO_EOF], 2));
	}

	/**
	 * Decompresses a file. Output file must be identical bit-by-bit to the
	 * original.
	 *
	 * @param in
	 *            Buffered bit stream of the file to be decompressed.
	 * @param out
	 *            Buffered bit stream writing to the output file.
	 */
	public void decompress(BitInputStream in, BitOutputStream out) {
		// check that file is compressed (first 32 bits will be a magic number)
		// readTreeHeader - will never have just 1 child, read through L then R child
		// readCompressedBits - read in 1 bit at a time, L on 0, R on 1 until no bits
		// left
		// check if correct
		int magic = in.readBits(BITS_PER_INT);
		if (magic != HUFF_TREE)
			throw new HuffException("No magic number");
		//System.out.println("made it here");
		HuffNode root = readTreeHeader(in);
		//System.out.println(root);
		readCompressedBits(root,root, in, out);
	}

	private void readCompressedBits(HuffNode realroot, HuffNode root, BitInputStream in, BitOutputStream out) {
		if (root==null)return;
		int val = root.value();
		while (val != PSEUDO_EOF) {
		if (root.left() == null && root.right() == null) {
			//System.out.println("leaf");
			out.writeBits(BITS_PER_WORD, val);
			root = realroot;}
		int bit = in.readBits(1);
		if (bit == 0) root=root.left();
		if (bit == 1) root=root.right();
		val = root.value();
		if (bit == -1) throw new HuffException("No EOF");}
	}
		
	private HuffNode readTreeHeader(BitInputStream in) {
		int bit = in.readBits(1);
		if (bit == 1) {
			int val = in.readBits(BITS_PER_WORD+1);
			return new HuffNode(val, 10, null, null);
			//System.out.println(ans);
			//return ans;
		}
		if (bit==0) {
			HuffNode left = readTreeHeader(in);
			HuffNode right = readTreeHeader(in);
			return new HuffNode(bit, 10, left, right);}
			//System.out.println(ans);
			//return ans;
		throw new HuffException("error");
	}

	public void setHeader(Header header) {
		myHeader = header;
		System.out.println("header set to " + myHeader);
	}
}