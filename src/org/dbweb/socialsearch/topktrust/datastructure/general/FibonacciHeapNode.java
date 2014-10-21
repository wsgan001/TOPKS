package org.dbweb.socialsearch.topktrust.datastructure.general;

public class FibonacciHeapNode<T> {
	T data;

    /**
     * first child node
     */
    FibonacciHeapNode<T> child;

    /**
     * left sibling node
     */
    FibonacciHeapNode<T> left;

    /**
     * parent node
     */
    FibonacciHeapNode<T> parent;

    /**
     * right sibling node
     */
    FibonacciHeapNode<T> right;

    /**
     * true if this node has had a child removed since this node was added to
     * its parent
     */
    boolean mark;

    /**
     * key value for this node
     */
    double key;

    /**
     * number of children of this node (does not count grandchildren)
     */
    int degree;

    //~ Constructors -----------------------------------------------------------

    /**
     * Default constructor. Initializes the right and left pointers, making this
     * a circular doubly-linked list.
     *
     * @param data data for this node
     * @param key initial key for node
     */
    public FibonacciHeapNode(T data, double key)
    {
        right = this;
        left = this;
        this.data = data;
        this.key = key;
    }

    //~ Methods ----------------------------------------------------------------

    /**
     * Obtain the key for this node.
     *
     * @return the key
     */
    public final double getKey()
    {
        return key;
    }

    /**
     * Obtain the data for this node.
     */
    public final T getData()
    {
        return data;
    }

    /**
     * Return the string representation of this object.
     *
     * @return string representing this object
     */
    public String toString()
    {
        return Double.toString(key);
    }
}

