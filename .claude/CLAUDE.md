Prefer Point to separate x and y coordinates
Prefer switches to polymorphism
In coordinates, x/col always precedes y/row
Put enums inside a larger class not in their own file

rlib contains the game model and logic.  it knows nothing about displaying anything on the screen.
rgame renders the game on the screen and operates the UI and pre