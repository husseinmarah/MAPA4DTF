package milo.utils;

import jade.lang.acl.ACLMessage;

/**
 * ACL Message utilities for JADE agents
 */
public class ACLUtil {
    
    /**
     * Set common headers for ACL messages
     * 
     * @param msg The message to set headers on
     * @param protocol The protocol to use
     * @param conversationId The conversation ID
     */
    public static void commonHeaders(ACLMessage msg, String protocol, String conversationId) {
        msg.setProtocol(protocol);
        if (conversationId != null) {
            msg.setConversationId(conversationId);
        }
    }
}