from mealplanner_ingest import main


def test_main_runs(capsys) -> None:  # type: ignore[no-untyped-def]
    main()
    assert "mealplanner-ingest" in capsys.readouterr().out
