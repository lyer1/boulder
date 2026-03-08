import os

with open('boulder/src/main/java/com/boulder/jdbc/PitonConnection.java', 'r') as f:
    conn_code = f.read()

# Let's clean up the stack traces that were added for debugging and empty catch blocks.
conn_code = conn_code.replace('e.printStackTrace();', '')

with open('boulder/src/main/java/com/boulder/jdbc/PitonConnection.java', 'w') as f:
    f.write(conn_code)
