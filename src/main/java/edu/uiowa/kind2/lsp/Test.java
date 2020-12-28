package edu.uiowa.kind2.lsp;

import java.io.IOException;
import java.net.Socket;

public class Test {
    public static void main(String[] args) {
        try {
            Socket s = new Socket("localhost", 23555);
            s.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
}
