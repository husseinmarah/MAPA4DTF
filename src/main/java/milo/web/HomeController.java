package milo.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HomeController {

    @GetMapping("/")
    public String home() {
        return """
            <html>
                <head>
                    <title>Backend Status</title>
                    <style>
                        body {
                            font-family: Arial, sans-serif;
                            background-color: #f4f4f4;
                            margin: 0;
                            padding: 40px;
                            text-align: center;
                        }
                        .container {
                            background: white;
                            padding: 30px;
                            border-radius: 10px;
                            max-width: 500px;
                            margin: auto;
                            box-shadow: 0 0 10px rgba(0,0,0,0.1);
                        }
                        a {
                            color: #007bff;
                            text-decoration: none;
                            font-weight: bold;
                        }
                        a:hover {
                            text-decoration: underline;
                        }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>Backend API is running ✔️</h1>
                        <p>Please access the Frontend UI at:</p>
                        <p>
                            <a href="http://localhost:3000" target="_blank">
                                http://localhost:3000
                            </a>
                        </p>
                    </div>
                </body>
            </html>
            """;
    }
}

