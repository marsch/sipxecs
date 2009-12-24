package org.sipfoundry.siptester;

import gov.nist.javax.sip.DialogExt;
import gov.nist.javax.sip.SipProviderExt;
import gov.nist.javax.sip.clientauthutils.AuthenticationHelper;
import gov.nist.javax.sip.message.RequestExt;
import gov.nist.javax.sip.message.ResponseExt;
import gov.nist.javax.sip.message.SIPRequest;
import gov.nist.javax.sip.message.SIPResponse;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.sip.ClientTransaction;
import javax.sip.Dialog;
import javax.sip.ResponseEvent;
import javax.sip.SipProvider;
import javax.sip.address.SipURI;
import javax.sip.message.Request;
import javax.sip.message.Response;

import org.apache.log4j.Logger;

public class SipClientTransaction extends SipTransaction implements
        Comparable<SipClientTransaction> {

    private static Logger logger = Logger.getLogger(SipClientTransaction.class);

    ConcurrentSkipListSet<SipResponse> sipResponses = new ConcurrentSkipListSet<SipResponse>();

    private ConcurrentSkipListSet<SipMessage> happensBefore = new ConcurrentSkipListSet<SipMessage>();

    private Endpoint endpoint;
    
    boolean processed;

    /*
     * The remote server transactions that this creates.
     */
    private Collection<SipServerTransaction> serverTransactions = new ConcurrentSkipListSet<SipServerTransaction>();


    private Semaphore preconditionsSem = new Semaphore(0);

   
    private SipMessage triggeringMessage;

    public SipClientTransaction(SipRequest sipRequest) {
        this.sipRequest = sipRequest;
        if ( sipRequest == null ) throw new NullPointerException("Null sipRequest");
        if (sipRequest.getTime() < minDelay) {
            minDelay = sipRequest.getTime();
        }
        this.delay = sipRequest.getTime() - minDelay;
    }

    public int compareTo(SipClientTransaction clientTx) {
        if (this.getDelay() == clientTx.getDelay())
            return 0;
        else if (this.getDelay() < clientTx.getDelay())
            return -1;
        else
            return 1;

    }

    public void addRequest(SipRequest sipRequest) {
         if ( sipRequest == null ) {
             throw new NullPointerException("null siprequest");
         }
         this.sipRequest = sipRequest;
    }

    public void addResponse(SipResponse sipResponse) {
        logger.debug("SipClientTransaction: addSipResponse : "
                    + sipResponse.getSipResponse().getFirstLine());
        this.sipResponses.add(sipResponse);     
    }

    public void addMatchingServerTransactions(Collection<SipServerTransaction> serverTransactions) {
        this.serverTransactions = serverTransactions;
    }

    public Collection<SipServerTransaction> getMatchingServerTransactions() {
        return this.serverTransactions;
    }

    /**
     * Get the dialog Ids associated with the clientTx.
     * 
     * @return
     */
    public HashSet<String> getDialogIds() {
        HashSet<String> dialogIds = new HashSet<String>();
        for (SipResponse sipResponse : this.sipResponses) {
            ResponseExt response = sipResponse.getSipResponse();
            if (response.getFromHeader().getTag() != null
                    && response.getToHeader().getTag() != null) {
                dialogIds.add(((SIPResponse) response).getDialogId(false));
            }
        }
        return dialogIds;
    }

    /**
     * Get the actual client transaction associated with the emulated ctx.
     */
    public void createAndSend() {
        try {
            RequestExt sipRequest = (RequestExt) this.sipRequest.getSipRequest();
            String transport = sipRequest.getTopmostViaHeader().getTransport();
            SipProviderExt provider = this.endpoint.getProvider(transport);
            System.out.println("createAndSend : " + sipRequest.getMethod() + " transactionId = " +
                    ((SIPRequest)this.getSipRequest().getSipRequest()).getTransactionId());
           
            if (this.triggeringMessage != null &&
                    triggeringMessage instanceof SipResponse && 
                    (((SipResponse)triggeringMessage).getStatusCode() == Response.PROXY_AUTHENTICATION_REQUIRED ||
                     ((SipResponse)triggeringMessage).getStatusCode() == Response.UNAUTHORIZED)) {
                this.processed  = true;
                this.handleChallenge();

            } else if (sipRequest.getFromHeader().getTag() != null
                    && sipRequest.getToHeader().getTag() != null) {
                String method = sipRequest.getMethod();
                if (method.equals(Request.ACK)) {
                    logger.debug("createAndSend ACK -- not sending");
                } else if (method.equals(Request.PRACK)) {
                    String dialogId = ((SIPRequest)sipRequest).getDialogId(false);
                    SipDialog sipDialog = SipTester.getDialog(dialogId);
                    Request prack = sipDialog.getDialog()
                            .createPrack(sipDialog.getLastResponse());
                    SipUtilities.copyHeaders(this.sipRequest.getSipRequest(), prack);
                    ClientTransaction clientTransaction = provider.getNewClientTransaction(prack);
                    clientTransaction.setApplicationData(this);
                    for (SipServerTransaction sipServerTransaction : this
                            .getMatchingServerTransactions()) {
                        sipServerTransaction.setBranch(((RequestExt) prack).getTopmostViaHeader()
                                .getBranch());
                    }
                    this.processed = true;
                    sipDialog.getDialog().sendRequest(clientTransaction);
                } else {
                    String dialogId = ((SIPRequest)sipRequest).getDialogId(false);
                    SipDialog sipDialog = SipTester.getDialog(dialogId);
                  
                    Request newRequest = sipDialog.getDialog().createRequest(
                            sipRequest.getMethod());
                    SipUtilities.copyHeaders(this.sipRequest.getSipRequest(), newRequest);
                    
                    ClientTransaction clientTransaction = provider
                            .getNewClientTransaction(newRequest);
                    clientTransaction.setApplicationData(this);
                    for (SipServerTransaction sipServerTransaction : this
                            .getMatchingServerTransactions()) {
                        sipServerTransaction.setBranch(((RequestExt) newRequest)
                                .getTopmostViaHeader().getBranch());
                    }
                    sipDialog.getDialog().sendRequest(clientTransaction);
                }
            } else {

                RequestExt newRequest = SipUtilities.createInviteRequest(this.sipRequest
                        .getSipRequest(), endpoint);
                ClientTransaction clientTransaction = provider
                        .getNewClientTransaction(newRequest);
                clientTransaction.setApplicationData(this);
                
                for (SipServerTransaction sipServerTransaction : this
                        .getMatchingServerTransactions()) {
                    sipServerTransaction.setBranch(((RequestExt) newRequest)
                            .getTopmostViaHeader().getBranch());
                }
                clientTransaction.sendRequest();

            }
        } catch (Exception ex) {
            SipTester.fail("unexpectedException", ex);
        }
    }

    /**
     * @param endpoint the endpoint to set
     */
    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * @return the endpoint
     */
    public Endpoint getEndpoint() {
        return endpoint;
    }


    public void handleChallenge() {
        try {
            
            Response response = ((SipResponse)triggeringMessage).getResponseEvent().getResponse();
            AuthenticationHelper authenticationHelper = this.endpoint.getStackBean()
                    .getAuthenticationHelper();
            ResponseEvent responseEvent = ((SipResponse) triggeringMessage).getResponseEvent();
            SipProvider sipProvider = (SipProvider) responseEvent.getSource();
            ClientTransaction challengedTx = responseEvent.getClientTransaction();
            ClientTransaction newClientTransaction = authenticationHelper.handleChallenge(
                    response, challengedTx, sipProvider, 5);
            ResponseExt responseExt = (ResponseExt) response;
            this.processed = true;
            newClientTransaction.setApplicationData(this);
            System.out.println("handleChallenge " + this.getTransactionId());
            if ( responseExt.getFromHeader().getTag() != null && responseExt.getToHeader().getTag() != null ) {
                newClientTransaction.getDialog().sendRequest(newClientTransaction);
            } else {
                newClientTransaction.sendRequest();
            }
        } catch (Exception ex) {
            SipTester.fail("handleChallenge : unexpected exception", ex);
        }

    }

    public void processResponse(ResponseEvent responseEvent) {
        try {
            ResponseExt response = (ResponseExt) responseEvent.getResponse();

            for ( SipResponse sipResponse : this.sipResponses ) {
                if ( response.getStatusCode() == sipResponse.getStatusCode()) {
                    sipResponse.setResponseEvent(responseEvent);
                }
            }
            /*
             * If this is a final response check if we have this final response in our set.
             */
            Dialog dialog = responseEvent.getDialog();
            if (response.getFromHeader().getTag() != null && response.getToHeader().getTag() != null  ) {
                SipDialog sipDialog = SipTester.getDialog(((SIPResponse) response).getDialogId(false));
                if (sipDialog != null) {
                    sipDialog.setLastResponse(response);
                    sipDialog.setDialog((DialogExt) dialog );
                }
            }

            if (response.getStatusCode() / 100 >= 2) {
                for (SipResponse sipResponse : this.sipResponses) {
                    int statusCode = sipResponse.getSipResponse().getStatusCode();
                    if (statusCode / 100 >= 2 && statusCode != response.getStatusCode()) {
                        SipTester.fail("Unexpected response seen! " + statusCode + " / "
                                + response.getStatusCode());
                    }
                }
                if (SipUtilities.getCSeqMethod(response).equals(Request.INVITE)
                        && response.getStatusCode() == Response.OK) {

                    long cseq = SipUtilities.getSequenceNumber(response);
                    Request ack = dialog.createAck(cseq);
                    dialog.sendAck(ack);
                }
            }
            for (SipResponse sipResponse : this.sipResponses) {
                /*
                 * Find a matching sipResponse where the status code matches the response status
                 * code.
                 */
                if (sipResponse.getSipResponse().getStatusCode() == response.getStatusCode()) {
                    for (SipClientTransaction sipClientTransaction : sipResponse
                            .getPostConditions()) {
                        
                        sipClientTransaction.removePrecondition(sipResponse);

                    }
                }
            }

        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(-1);
        }

    }
    
    public String getFromTag() {
        return this.sipRequest.getSipRequest().getFromHeader().getTag();
    }
    
    public String getCallId() {
        return this.sipRequest.getSipRequest().getCallIdHeader().getCallId();
    }
    
    public String getMethod() {
        return this.sipRequest.getSipRequest().getMethod();
    }

   

    public void removePrecondition(SipMessage sipMessage) {
        this.happensBefore.remove(sipMessage);
        if (happensBefore.isEmpty()) {
            this.triggeringMessage = sipMessage;
            
            this.preconditionsSem.release();
        }
    }

    public boolean checkPreconditions() {
        try {
            if (this.happensBefore.isEmpty()) {
                System.out.println("precondition is empty");
                return true;
            } else {
              //  this.printHappensBefore();
                boolean retval =  this.preconditionsSem.tryAcquire(100, TimeUnit.SECONDS);
                if (! retval ) {
                    SipTester.fail("Could not satisfy precondition");        
                }
                return retval;
            }
        } catch (Exception ex) {
            SipTester.fail("Unexpected exception ", ex);
            return false;
        }
    }

    public void addHappensBefore(SipMessage sipMessage) {
        this.happensBefore.add(sipMessage);
    }

    public void printHappensBefore() {
     
        SipTester.getPrintWriter().println("<happens-before>");
        for (SipMessage sipMessage : this.happensBefore) {
            SipTester.getPrintWriter().println("<sip-message><![CDATA[" + sipMessage.getSipMessage() + "]]></sip-message>");
        }
        SipTester.getPrintWriter().println("</happens-before>");
    }

    public void printResponses() {
        SipTester.getPrintWriter().println("<responses>");
        for (SipResponse sipResponse : this.sipResponses ) {
            SipTester.getPrintWriter().println("<response>");
            SipTester.getPrintWriter().println("<sip-response><![CDATA[" + sipResponse.getSipResponse() + "]]></sip-response>");
            SipTester.getPrintWriter().println("<post-condition>");
            for (SipClientTransaction postCondition : sipResponse.getPostConditions()) {
                SipTester.getPrintWriter().println("<triggers-transaction>" + postCondition.getTransactionId() + "</triggers-transaction>" );
            }
            SipTester.getPrintWriter().println("</post-condition>");
            SipTester.getPrintWriter().println("</response>");
        }
        SipTester.getPrintWriter().println("</responses>");
    }
    public void printTransaction() {
        SipTester.getPrintWriter().println("<sip-client-transaction>");
        SipTester.getPrintWriter().println("<transaction-id>" + this.getTransactionId() + "</transaction-id>");
        SipTester.getPrintWriter().println("<sip-request><![CDATA[" + this.getSipRequest().getSipRequest() + "]]></sip-request>");
        printHappensBefore();
        printResponses();
        SipTester.getPrintWriter().println("</sip-client-transaction>\n");
        
    }

}