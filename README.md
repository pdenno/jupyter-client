# jupyter-client

A Clojure library to interact with a jupyter kernel.

This library allows you to run [execute_requests](https://jupyter-client.readthedocs.io/en/stable/messaging.html)
against a jupyter kernel and view what comes back from the kernel's stdout (if anything). 

## Usage

```clojure
(req-msg :config-file "/Users/pdenno/Library/Jupyter/runtime/kernel-5aae1612-b3e1-46a1-b926-c6ab30a94d7e.json"
         :code (str "foobar = 'Greetings from Clojure!'\n"
                    "print(foobar)"))
```
Returns `{:status :ok, :stdout "Greetings from Clojure!\n"}` and sets the variable foobar in the kernel.
(Note that this example is running against a Python kernel.)

Limitations:

Limitation 1: The code doesn't currently generate signed messages correctly. Therefore, it is necessary
to turn signature checking off in the kernel. For a jupyter lab kernel, for example, this is achieved
by the following line in ~/.jupyter/jupyter_notebook_config.py:

`c.Session.key = b''`

Similarly, if you are using jupyter console, or perhaps clojupyter (that not yet been tested) connect.json
should contain `"key": "",`. 

Limitation 2: I have provided no tests in the testing directory. Shame on me. I'll fix it soon, I hope.

## License

EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

Copyright © 2019 Peter Denno

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
