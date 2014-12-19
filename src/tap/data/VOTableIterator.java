package tap.data;

import java.io.IOException;
import java.io.InputStream;
import java.util.NoSuchElementException;

import tap.TAPException;
import tap.metadata.TAPColumn;
import tap.metadata.VotType;
import tap.metadata.VotType.VotDatatype;
import uk.ac.starlink.table.ColumnInfo;
import uk.ac.starlink.table.DescribedValue;
import uk.ac.starlink.table.StarTable;
import uk.ac.starlink.table.StarTableFactory;
import uk.ac.starlink.table.TableBuilder;
import uk.ac.starlink.table.TableFormatException;
import uk.ac.starlink.table.TableSink;
import adql.db.DBType;

/**
 * <p>{@link TableIterator} which lets iterate over a VOTable input stream using STIL.</p>
 * 
 * <p>{@link #getColType()} will return TAP type based on the type declared in the VOTable metadata part.</p>
 * 
 * @author Gr&eacute;gory Mantelet (ARI)
 * @version 2.0 (12/2014)
 * @since 2.0
 */
public class VOTableIterator implements TableIterator {

	/**
	 * <p>This class lets consume the metadata and rows of a VOTable document.</p>
	 * 
	 * <p>
	 * 	On the contrary to a usual TableSink, this one will stop after each row until this row has been fetched by {@link VOTableIterator}.
	 * </p>
	 * 
	 * <p>
	 * 	Besides, the metadata returned by StarTable are immediately converted into TAP metadata. If this conversion fails, the error is kept
	 * 	in metaError, so that the VOTable reading can continue if the fact that metadata are missing is not a problem for the class using the
	 * 	{@link VOTableIterator}.
	 * </p> 
	 * 
	 * @author Gr&eacute;gory Mantelet (ARI)
	 * @version 2.0 (12/2014)
	 * @since 2.0
	 */
	protected static class StreamVOTableSink implements TableSink {

		/** <p>The accepted VOTable metadata, after conversion from StarTable metadata.</p>
		 * <p><i>Note: this may be NULL after the metadata has been read if an error occurred while performing the conversion.
		 * In this case, metaError contains this error.</> */
		private TAPColumn[] meta = null;
		
		/** The error which happened while converting the StarTable metadata into TAP metadata. */
		private DataReadException metaError = null;
		
		/** The last accepted row. */
		private Object[] pendingRow = null;
		
		/** Flag meaning that the end of the stream has been reached
		 * OR if the VOTable reading should be stopped before reading more rows. */
		private boolean endReached = false;
		
		/**
		 * <p>Stop nicely reading the VOTable.</p>
		 * 
		 * <p>
		 * 	An exception will be thrown to the STILTS class using this TableSink,
		 * 	but no exception should be thrown to VOTableIterator.
		 * </p>
		 */
		public synchronized void stop(){
			endReached = true;
			notifyAll();
		}
		
		@Override
		public synchronized void acceptMetadata(final StarTable metaTable) throws TableFormatException {
			try{
				// Convert the StartTable metadata into TAP metadata:
				meta = extractColMeta(metaTable);
				
			}catch(DataReadException dre){
				// Save the error ; this error will be throw when a call to getMetadata() will be done:
				metaError = dre;
				
			}finally{
				// Free all waiting threads:
				notifyAll();
			}
		}

		@Override
		public synchronized void acceptRow(final Object[] row) throws IOException {
			try{
				// Wait until the last accepted row has been consumed: 
				while(!endReached && pendingRow != null)
					wait();
				
				/* If the end has been reached, this is not normal
				 * (because endRows() is always called after acceptRow()...so, it means the iteration has been aborted before the end)
				 * and so the stream reading should be interrupted: */
				if (endReached)
					throw new IOException("Streaming aborted!");
				
				// Otherwise, keep the given row:
				pendingRow = row;

				/* Security for the cases where a row to accept is NULL.
				 * In such case, pendingRow will be set to NULL and the function getRow() will wait for ever.
				 * This case is not supposed to happen because the caller of acceptRow(...) should not give a NULL row...
				 * ...which should then mean that the end of the stream has been reached. */
				if (pendingRow == null)
					endReached = true;
				
			}catch(InterruptedException ie){
				/* If the thread has been interrupted, set this TableSink in a state similar to
				 * when the end of the stream has been reached: */
				pendingRow = null;
				endReached = true;
				
			}finally{
				// In all cases, all waiting threads must be freed:
				notifyAll();
			}
		}

		@Override
		public synchronized void endRows() throws IOException {
			// No more rows are available:
			pendingRow = null;
			// Set the END flag:
			endReached = true;
			// Notify all waiting threads that the end has been reached:
			notifyAll();
		}
		
		/**
		 * <p>Get the metadata found in the VOTable.</p>
		 * 
		 * <p><i>Note:
		 * 	This method is blocking until metadata are fully available by this TableSink
		 * 	or if an error occurred while converting them in TAP metadata.
		 * 	A Thread interruption will also make this function returning.
		 * </i></p>
		 * 
		 * @return The metadata found in the VOTable header.
		 * 
		 * @throws DataReadException	If the metadata can not be interpreted correctly.
		 */
		public synchronized TAPColumn[] getMeta() throws DataReadException{
			try{
				// Wait until metadata are available, or if an error has occurred while accepting them:
				while(metaError == null && meta == null)
					wait();
				
				// If there was an error while interpreting the accepted metadata, throw it:
				if (metaError != null)
					throw metaError;
				
				// Otherwise, just return the metadata:
				return meta;
				
			}catch(InterruptedException ie){
				/* If the thread has been interrupted, set this TableSink in a state similar to
				 * when the end of the stream has been reached: */
				endReached = true;
				/* Return the metadata ;
				 * NULL will be returned if the interruption has occurred before the real reading of the VOTable metadata: */
				return meta;
				
			}finally{
				// In all cases, the waiting threads must be freed:
				notifyAll();
			}
		}
		
		/**
		 * <p>Get the last accepted row.</p>
		 * 
		 * <p><i>Note:
		 * 	This function is blocking until a row has been accepted or the end of the stream has been reached.
		 * 	A Thread interruption will also make this function returning.
		 * </i></p>
		 * 
		 * @return
		 */
		public synchronized Object[] getRow() {
			try{
				// Wait until a row has been accepted or the end has been reached:
				while(!endReached && pendingRow == null)
					wait();
				
				// If there is no more rows, just return NULL (meaning for the called "end of stream"):
				if (endReached)
					return null;
				
				/* Otherwise, reset pendingRow to NULL in order to enable the reading of the next row,
				 * and finally return the last accepted row: */
				Object[] row = pendingRow;
				pendingRow = null;
				return row;
				
			}catch(InterruptedException ie){
				/* If the thread has been interrupted, set this TableSink in a state similar to
				 * when the end of the stream has been reached: */
				endReached = true;
				// Return NULL, meaning the end of the stream has been reached:
				return null;
				
			}finally {
				// In all cases, the waiting threads must be freed:
				notifyAll();
			}
		}
		
		/**
		 * Extract an array of {@link TAPColumn} objects. Each corresponds to one of the columns listed in the given table,
		 * and so corresponds to the metadata of a column. 
		 * 
		 * @param table		{@link StarTable} which contains only the columns' information.
		 * 
		 * @return			The corresponding list of {@link TAPColumn} objects.
		 * 
		 * @throws DataReadException	If there is a problem while resolving the field datatype (for instance: unknown datatype, a multi-dimensional array is provided, a bad number format for the arraysize).
		 */
		protected TAPColumn[] extractColMeta(final StarTable table) throws DataReadException{
			// Count the number columns and initialize the array:
			TAPColumn[] columns = new TAPColumn[table.getColumnCount()];

			// Add all columns meta:
			for(int i = 0; i < columns.length; i++){
				// get the field:
				ColumnInfo colInfo = table.getColumnInfo(i);

				// get the datatype:
				String datatype = getAuxDatumValue(colInfo, "Datatype");

				// get the arraysize:
				String arraysize = ColumnInfo.formatShape(colInfo.getShape());

				// get the xtype:
				String xtype = getAuxDatumValue(colInfo, "xtype");

				// Resolve the field type:
				DBType type;
				try{
					type = resolveVotType(datatype, arraysize, xtype).toTAPType();
				}catch(TAPException te){
					if (te instanceof DataReadException)
						throw (DataReadException)te;
					else
						throw new DataReadException(te.getMessage(), te);
				}

				// build the TAPColumn object:
				TAPColumn col = new TAPColumn(colInfo.getName(), type, colInfo.getDescription(), colInfo.getUnitString(), colInfo.getUCD(), colInfo.getUtype());
				col.setPrincipal(false);
				col.setIndexed(false);
				col.setStd(false);

				// append it to the array:
				columns[i] = col;
			}

			return columns;
		}

		/**
		 * Extract the specified auxiliary datum value from the given {@link ColumnInfo}.
		 * 
		 * @param colInfo			{@link ColumnInfo} from which the auxiliary datum must be extracted.
		 * @param auxDatumName		The name of the datum to extract.
		 * 
		 * @return	The extracted value as String.
		 */
		protected String getAuxDatumValue(final ColumnInfo colInfo, final String auxDatumName){
			DescribedValue value = colInfo.getAuxDatumByName(auxDatumName);
			return (value != null) ? value.getValue().toString() : null;
		}

		/**
		 * Resolve a VOTable field type by using the datatype, arraysize and xtype strings as specified in a VOTable document.
		 * 
		 * @param datatype		Attribute value of VOTable corresponding to the datatype.
		 * @param arraysize		Attribute value of VOTable corresponding to the arraysize.
		 * @param xtype			Attribute value of VOTable corresponding to the xtype.
		 * 
		 * @return	The resolved VOTable field type, or a CHAR(*) type if the specified type can not be resolved.
		 * 
		 * @throws DataReadException	If a field datatype is unknown.
		 */
		protected VotType resolveVotType(final String datatype, final String arraysize, final String xtype) throws DataReadException{
			// If no datatype is specified, return immediately a CHAR(*) type:
			if (datatype == null || datatype.trim().length() == 0)
				return new VotType(VotDatatype.CHAR, "*");

			// Identify the specified datatype:
			VotDatatype votdatatype;
			try{
				votdatatype = VotDatatype.valueOf(datatype.toUpperCase());
			}catch(IllegalArgumentException iae){
				throw new DataReadException("unknown field datatype: \"" + datatype + "\"");
			}

			// Build the VOTable type:
			return new VotType(votdatatype, arraysize, xtype);
		}
		
	}
	
	/** Stream containing the VOTable on which this {@link TableIterator} is iterating. */
	protected final InputStream input;
	/** The StarTable consumer which is used to iterate on each row. */
	protected final StreamVOTableSink sink;

	/** Indicate whether the row iteration has already started. */
	protected boolean iterationStarted = false;
	/** Indicate whether the last row has already been reached. */
	protected boolean endReached = false;
	
	/** The last read row. Column iteration is done on this array. */
	protected Object[] row;
	/** Index of the last read column (=0 just after {@link #nextRow()} and before {@link #nextCol()}, ={@link #nbColumns} after the last column has been read). */
	protected int indCol = -1;
	/** Number of columns available according to the metadata. */
	protected int nbCol = 0;
	
	/**
	 * Build a TableIterator able to read rows and columns inside the given VOTable input stream.
	 * 
	 * @param input	Input stream over a VOTable document.
	 * 
	 * @throws NullPointerException	If NULL is given in parameter.
	 * @throws DataReadException	If the given VOTable can not be parsed.
	 */
	public VOTableIterator(final InputStream input) throws DataReadException{
		// An input stream MUST BE provided:
		if (input == null)
			throw new NullPointerException("Missing VOTable document input stream over which to iterate!");
		this.input = input;
		
		try{

			// Set the VOTable builder/interpreter:
			final TableBuilder tb = (new StarTableFactory()).getTableBuilder("votable");

			// Build the TableSink to use:
			sink = new StreamVOTableSink();
			
			// Initiate the stream process:
			Thread streamThread = new Thread() {
                public void run() {
                    try{
            			tb.streamStarTable(input, sink, null);
                    }catch(IOException e) {
                    	if (e.getMessage() != null && !e.getMessage().equals("Reading interrupted!"))
                    		e.printStackTrace();
                    }
                }
            };
            streamThread.start();

		}catch(Exception ex){
			throw new DataReadException("Unable to parse/read the given VOTable input stream!", ex);
		}
	}

	@Override
	public TAPColumn[] getMetadata() throws DataReadException {
		return sink.getMeta();
	}

	@Override
	public boolean nextRow() throws DataReadException {
		// If no more rows, return false directly:
		if (endReached)
			return false;
		
		// Fetch the row:
		row = sink.getRow();
		
		// Reset the column iteration:
		if (!iterationStarted){
			iterationStarted = true;
			nbCol = sink.getMeta().length;
		}
		indCol = 0;
		
		// Tells whether there is more rows or not:
		endReached = (row == null);
		return !endReached;
	}

	@Override
	public boolean hasNextCol() throws IllegalStateException, DataReadException {
		// Check the read state:
		checkReadState();

		// Determine whether the last column has been reached or not:
		return (indCol < nbCol);
	}

	@Override
	public Object nextCol() throws NoSuchElementException, IllegalStateException, DataReadException {
		// Check the read state and ensure there is still at least one column to read:
		if (!hasNextCol())
			throw new NoSuchElementException("No more field to read!");

		// Get the column value:
		return row[indCol++];
	}

	@Override
	public DBType getColType() throws IllegalStateException, DataReadException {
		// Basically check the read state (for rows iteration):
		checkReadState();

		// Check deeper the read state (for columns iteration):
		if (indCol <= 0)
			throw new IllegalStateException("No field has yet been read!");
		else if (indCol > nbCol)
			throw new IllegalStateException("All fields have already been read!");

		// Return the column type:
		return sink.getMeta()[indCol - 1].getDatatype();
	}

	@Override
	public void close() throws DataReadException {
		endReached = true;
		sink.stop();
		// input.close(); // in case sink.stop() is not enough to stop the VOTable reading!
	}

	/**
	 * <p>Check the row iteration state. That's to say whether:</p>
	 * <ul>
	 * 	<li>the row iteration has started = the first row has been read = a first call of {@link #nextRow()} has been done</li>
	 * 	<li>AND the row iteration is not finished = the last row has been read.</li>
	 * </ul>
	 * @throws IllegalStateException
	 */
	protected void checkReadState() throws IllegalStateException{
		if (!iterationStarted)
			throw new IllegalStateException("No row has yet been read!");
		else if (endReached)
			throw new IllegalStateException("End of VOTable file already reached!");
	}

}
