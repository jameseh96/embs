# To Build:

## Unix:

    mrc --verbose --assembly=TXRX-1.0 ./transport/{Routing,TransmitBuffer,TXRX,DataFrame,Frame}.java  -r:logger-11.0


## Windows:
    mrc --verbose --assembly=Transmission-1.0 ./transport/Routing.java ./transport/TransmitBuffer.java ./transport/TXRX.java ./transport/DataFrame.java ./transport/Frame.java  -r:logger-11.0
